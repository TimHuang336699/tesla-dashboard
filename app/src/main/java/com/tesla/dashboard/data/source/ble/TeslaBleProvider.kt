package com.tesla.dashboard.data.source.ble

import android.util.Log
import com.tesla.dashboard.data.model.VehicleData
import com.tesla.dashboard.data.source.VehicleDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.security.PrivateKey
import java.security.PublicKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Tesla BLE 数据源 Provider — 基于蓝牙直连获取全部车辆数据
 *
 * 替代原有的 TeslaApiProvider (Fleet API 轮询) 和 GNSS/Sensor 本地传感器，
 * 通过 BLE 蓝牙直连获取所有车辆数据。
 *
 * ## 双域通信架构
 *
 * 同一 BLE GATT 连接与两个 Domain 分别握手，各域拥有独立加密会话:
 *
 * ### VCSEC 域 (DOMAIN_VEHICLE_SECURITY = 2)
 * - 唤醒车辆 (RKE_ACTION_WAKE_VEHICLE)
 * - 车辆安全状态 (锁/车门/后备箱)
 * - 钥匙白名单管理
 *
 * ### Infotainment 域 (DOMAIN_INFOTAINMENT = 3)
 * - GetVehicleState: 获取完整车辆状态
 *   - DriveState: 车速 / 位置 / 航向 / 档位 / 海拔
 *   - ChargeState: 电池 SOC / 续航里程
 *   - ClimateState: 车内/车外温度
 *   - CarState: 里程表 / 距离单位
 *
 * ## 导出数据计算
 *
 * 所有数据均来自车辆 BLE，通过差值计算以下导出量:
 * - **加速度**: 由车速变化率 (Δv/Δt) 计算，单位 m/s²
 * - **横向加速度**: 由航向变化率 × 车速计算，单位 m/s²
 * - **G 力**: √(纵向² + 横向²) / 9.81
 * - **行程里程**: 由里程表差值累加，单位 km
 * - **瞬时电耗**: 由 SOC 变化 + 里程差值计算 (在 VehicleData.computeConsumption 中)
 *
 * ## 核心优势
 * - 零 API 费用 (不经过 Tesla 云端)
 * - 毫秒级响应 (本地蓝牙通信)
 * - 无网络依赖 (地下车库等弱网场景可用)
 * - 距离限制 (蓝牙范围内约 10 米)
 *
 * @param bleManager BLE GATT 通信管理器
 * @param keyManager 密钥管理器
 */
@Singleton
class TeslaBleProvider @Inject constructor(
    private val bleManager: TeslaBleManager,
    private val keyManager: TeslaKeyManager,
) : VehicleDataSource {

    private val TAG = "TeslaBleProvider"

    /** 车辆 VIN (运行时配置) */
    @Volatile
    var vin: String? = null

    /** BLE 连接状态 */
    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: Flow<Boolean> = _isAvailable.asStateFlow()

    /** 配对状态 */
    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: Flow<PairingState> = _pairingState.asStateFlow()

    /** 当前 BLE 会话 */
    @Volatile
    private var session: BleSession? = null

    /** 是否正在轮询 */
    @Volatile
    private var isPolling = false

    /**
     * 当前配对协程的 Job 句柄 — 用于在 [cancelPairing] / 退出页面时主动取消,
     * 避免协程残留导致下次进入配对页时无法启动新会话。
     */
    @Volatile
    private var pairingJob: kotlinx.coroutines.Job? = null

    // ===== BLE 数据状态追踪 (用于计算加速度/行程里程/电耗) =====

    /** 上一次轮询车速 km/h (用于纵向加速度计算) */
    @Volatile
    private var prevSpeedKmh: Float? = null

    /** 上一次轮询航向角 (用于横向加速度计算) */
    @Volatile
    private var prevHeading: Float? = null

    /** 上一次轮询里程表 km (用于行程里程计算) */
    @Volatile
    private var prevOdometerKm: Float? = null

    /** 上一次轮询时间戳 ms (用于加速度计算的时间差) */
    @Volatile
    private var prevTimestampMs: Long = 0L

    /** 本次行程累计里程 km (由里程表差值累加) */
    @Volatile
    private var accumulatedTripDistanceKm: Float = 0f

    /** 行程是否已开始 (首次收到有效里程表时开始) */
    @Volatile
    private var tripStarted: Boolean = false

    // ===== VehicleDataSource 实现 =====

    /**
     * 观察车辆数据流
     *
     * 通过 BLE 轮询车辆状态，每次轮询:
     * 1. 确保已配对且有密钥
     * 2. 扫描并连接车辆 BLE
     * 3. ECDH 握手建立加密会话
     * 4. 唤醒车辆
     * 5. 查询车辆状态
     * 6. 解析并发射 VehicleData
     *
     * 轮询间隔: 10 秒 (BLE 通信延迟低，可较频繁)
     */
    override fun observeData(): Flow<VehicleData> = flow {
        val currentVin = vin
        if (currentVin.isNullOrBlank()) {
            _isAvailable.value = false
            emit(VehicleData(isTeslaConnected = false))
            return@flow
        }

        // 检查是否有已保存的密钥
        val hasKey = keyManager.hasKeyPair()
        if (!hasKey) {
            _isAvailable.value = false
            emit(VehicleData(isTeslaConnected = false))
            return@flow
        }

        while (true) {
            try {
                val vehicleData = pollVehicleState(currentVin)
                if (vehicleData != null) {
                    _isAvailable.value = true
                    emit(vehicleData)
                } else {
                    _isAvailable.value = false
                    emit(VehicleData(isTeslaConnected = false))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Poll error: ${e.message}")
                _isAvailable.value = false
                emit(VehicleData(isTeslaConnected = false))
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    override suspend fun start() {
        // 性能优化: 仅在内存中检查 vin,文件 IO (loadPairedVin/hasKeyPair) 改为后台执行
        // 这样 start() 调用在主线程上几乎瞬时返回, 不会因为读 SP 而阻塞首帧渲染
        if (!vin.isNullOrBlank()) {
            return  // 已有 VIN,无需读盘
        }
        // 文件 IO 移到 IO 调度器, 不阻塞 start() 调用方
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pairedVin = keyManager.loadPairedVin()
            if (!pairedVin.isNullOrBlank()) {
                vin = pairedVin
                Log.i(TAG, "Auto-loaded paired VIN: $pairedVin")
            }
            // 验证配对状态 (检查是否有密钥)
            if (vin.isNullOrBlank() || !keyManager.hasKeyPair()) {
                _isAvailable.value = false
            }
        }
    }

    override suspend fun stop() {
        isPolling = false
        disconnectSession()
        _isAvailable.value = false
    }

    /**
     * 重置行程数据
     *
     * 清零行程累计里程和状态追踪变量。
     * 应在用户开始新行程时调用。
     */
    fun resetTrip() {
        accumulatedTripDistanceKm = 0f
        tripStarted = false
        prevOdometerKm = null
        prevSpeedKmh = null
        prevHeading = null
        prevTimestampMs = 0L
        Log.i(TAG, "Trip data reset")
    }

    // ===== 配对流程 =====

    /**
     * 开始配对流程
     *
     * 完整流程:
     * 1. 生成新的 ECC 密钥对
     * 2. 扫描并连接车辆 BLE
     * 3. ECDH 握手建立临时会话
     * 4. 发送 add-key-request (WhitelistOperation)
     * 5. 等待用户在车机上用 NFC 卡片确认
     * 6. 保存密钥对和 VIN
     *
     * 注意: 该方法会先取消任何残留的 [pairingJob] (防止中途退出页面后旧协程仍在运行),
     * 然后使用调用方所在作用域的协程运行配对流程。
     *
     * @param vin 车辆识别号
     * @param scanTimeoutMs 扫描/连接超时(毫秒),默认 [TeslaBleConstants.CONNECT_TIMEOUT_MS] = 10s
     * @param nfcTimeoutMs 等待 NFC 确认超时(毫秒),默认 [TeslaBleConstants.NFC_CONFIRM_TIMEOUT_MS] = 30s
     * @return 配对是否成功
     */
    suspend fun startPairing(
        vin: String,
        scanTimeoutMs: Long = TeslaBleConstants.CONNECT_TIMEOUT_MS,
        nfcTimeoutMs: Long = TeslaBleConstants.NFC_CONFIRM_TIMEOUT_MS,
    ): Boolean {
        // 防止旧的配对协程残留: 先取消,再启动新的
        pairingJob?.takeIf { it.isActive }?.cancel()
        // 先把状态重置为 Idle, 避免新流程被旧状态干扰
        _pairingState.value = PairingState.Idle
        // 清理可能残留的 GATT 连接
        if (session != null) {
            disconnectSession()
        }
        bleManager.disconnect()

        _pairingState.value = PairingState.GeneratingKey
        Log.i(TAG, "Starting pairing for VIN=$vin (scanTimeout=${scanTimeoutMs}ms, nfcTimeout=${nfcTimeoutMs}ms)")

        try {
            // 1. 生成密钥对
            val (privateKey, publicKey, publicKeyRaw) = keyManager.generateKeyPair()
            Log.i(TAG, "Generated ECC key pair")

            // 2. 扫描车辆
            _pairingState.value = PairingState.Scanning
            val device = withTimeoutOrNull(scanTimeoutMs) {
                bleManager.scanForVehicle(vin)
            } ?: run {
                _pairingState.value = PairingState.Failed("扫描车辆失败,请确认车辆在附近且蓝牙已开启")
                return false
            }

            // 3. 连接 GATT
            _pairingState.value = PairingState.Connecting
            bleManager.connect(device)

            // 4. ECDH 握手 (使用新生成的临时密钥)
            _pairingState.value = PairingState.Handshaking
            val tempSession = performHandshake(privateKey, publicKeyRaw)
            Log.i(TAG, "Handshake completed, counter=${tempSession.counter}")

            // 5. 发送 add-key-request
            _pairingState.value = PairingState.SendingPairRequest
            val addKeyPayload = TeslaBleMessages.encodeAddKeyRequest(
                publicKeyRaw = publicKeyRaw,
                keyRole = TeslaBleConstants.ROLE_OWNER,
                keyFormFactor = TeslaBleConstants.KEY_FORM_FACTOR_CLOUD_KEY,
            )
            sendSignedCommand(tempSession, addKeyPayload, TeslaBleConstants.DOMAIN_VEHICLE_SECURITY)
            Log.i(TAG, "Add-key-request sent")

            // 6. 等待车机确认 (用户需在车机上用 NFC 卡片确认)
            _pairingState.value = PairingState.WaitingForNfcConfirmation
            // 等待车辆响应 — 使用 30s 超时, 用户刷 NFC 卡片通常 < 10s
            val nfcResult = withTimeoutOrNull(nfcTimeoutMs) {
                bleManager.receiveMessage(nfcTimeoutMs)
            }
            if (nfcResult == null) {
                _pairingState.value = PairingState.Failed("等待 NFC 确认超时,请重新在车机刷 NFC 卡片")
                return false
            }

            // 7. 保存密钥
            _pairingState.value = PairingState.SavingKey
            keyManager.saveKeyPair(privateKey, publicKey)
            keyManager.savePairedVin(vin)
            this.vin = vin

            _pairingState.value = PairingState.Completed
            Log.i(TAG, "Pairing completed successfully")
            return true

        } catch (e: CancellationException) {
            // 协程被取消(用户退出页面或主动取消) — 不显示错误, 重置为空闲
            Log.i(TAG, "Pairing cancelled by user")
            _pairingState.value = PairingState.Idle
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Pairing failed: ${e.message}", e)
            _pairingState.value = PairingState.Failed(e.message ?: "配对失败")
            return false
        } finally {
            pairingJob = null
            disconnectSession()
        }
    }

    /**
     * 主动取消当前配对流程
     *
     * 当用户在配对中途按返回/退出页面时, 必须调用本方法取消残留协程,
     * 否则:
     * - 协程仍在等待 `receiveMessage(30000ms)`, GATT 连接未释放
     * - 下次进入配对页时, GATT 资源被旧连接占用, 新配对会超时
     * - pairingState 仍停留在 WaitingForNfcConfirmation, UI 显示卡死
     *
     * 取消后状态会被 [startPairing] 重置为 Idle, 不影响后续重新配对。
     */
    fun cancelPairing() {
        val job = pairingJob
        if (job != null && job.isActive) {
            Log.i(TAG, "Cancelling active pairing job")
            job.cancel()
        }
        pairingJob = null
        // 强制断开可能残留的 GATT 连接
        if (session != null) {
            disconnectSession()
        }
        bleManager.disconnect()
        // 重置状态 (仅当当前不是 Completed/Failed 终态时)
        if (_pairingState.value !is PairingState.Completed) {
            _pairingState.value = PairingState.Idle
        }
    }

    /**
     * 解除配对
     *
     * 清除本地密钥和配对信息。
     * 注意: 不会从车辆信任列表中移除公钥 (需要车机操作)。
     */
    suspend fun unpair() {
        keyManager.clearAll()
        vin = null
        _pairingState.value = PairingState.Idle
        _isAvailable.value = false
        Log.i(TAG, "Unpaired and keys cleared")
    }

    // ===== BLE 会话管理 =====

    /**
     * 执行 ECDH 握手
     *
     * 1. 发送 SessionInfoRequest (包含客户端公钥)
     * 2. 接收 SessionInfo (包含车辆公钥、epoch、counter)
     * 3. 计算 ECDH 共享密钥
     *
     * 同一 BLE GATT 连接可与不同 Domain (VCSEC / Infotainment) 分别握手,
     * 各 Domain 拥有独立的 session key 和 counter。
     *
     * @param privateKey 客户端私钥
     * @param publicKeyRaw 客户端公钥 (65 字节未压缩格式)
     * @param domain 目标 Domain (默认 VCSEC)
     * @return BLE 会话信息
     */
    private suspend fun performHandshake(
        privateKey: PrivateKey,
        publicKeyRaw: ByteArray,
        domain: Int = TeslaBleConstants.DOMAIN_VEHICLE_SECURITY,
    ): BleSession {
        // 构建握手消息
        val handshakeMsg = TeslaBleMessages.buildHandshakeMessage(
            publicKeyRaw = publicKeyRaw,
            domain = domain,
        )

        // 发送并等待响应
        val responseBytes = bleManager.sendAndWait(handshakeMsg)

        // 解析响应
        val response = TeslaBleMessages.parseRoutableMessage(responseBytes)
        val sessionInfoBytes = response.sessionInfo
            ?: throw IllegalStateException("No SessionInfo in handshake response")

        // 解析 SessionInfo
        val sessionInfo = TeslaBleMessages.parseSessionInfo(sessionInfoBytes)
        Log.d(TAG, "SessionInfo: counter=${sessionInfo.counter}, epoch=${sessionInfo.epoch.size} bytes")

        // 从未压缩格式解码车辆公钥
        val vehiclePublicKey = TeslaCrypto.decodePublicKey(sessionInfo.vehiclePublicKey)

        // 计算 ECDH 共享密钥
        val sharedKey = TeslaCrypto.computeSharedKey(privateKey, vehiclePublicKey)

        return BleSession(
            sharedKey = sharedKey,
            epoch = sessionInfo.epoch,
            counter = sessionInfo.counter,
            vehiclePublicKey = vehiclePublicKey,
        )
    }

    /**
     * 发送已签名命令
     *
     * 1. 序列化命令 payload (protobuf)
     * 2. 构造 TLV 元数据
     * 3. AES-GCM 加密
     * 4. 构建签名数据
     * 5. 组装 RoutableMessage 并发送
     *
     * @param session 当前 BLE 会话
     * @param payload 命令 payload (protobuf 编码)
     * @param domain 目标 Domain
     */
    private suspend fun sendSignedCommand(
        session: BleSession,
        payload: ByteArray,
        domain: Int,
    ) {
        // 递增计数器
        session.counter++

        // 构造 nonce
        val nonce = TeslaCrypto.buildNonce(session.epoch, session.counter)

        // 构造 TLV 元数据 (作为 AES-GCM 的 AAD)
        val expiresAt = TeslaCrypto.computeExpiresAt()
        val tlvEntries = listOf(
            TeslaBleConstants.TAG_SIGNATURE_TYPE to
                TeslaCrypto.uint32ToBytes(TeslaBleConstants.SIGNATURE_TYPE_AES_GCM_PERSONALIZED),
            TeslaBleConstants.TAG_DOMAIN to
                TeslaCrypto.uint32ToBytes(domain),
            TeslaBleConstants.TAG_PERSONALIZATION to
                (vin?.toByteArray() ?: ByteArray(0)),
            TeslaBleConstants.TAG_EPOCH to session.epoch,
            TeslaBleConstants.TAG_EXPIRES_AT to
                TeslaCrypto.uint32ToBytes(expiresAt),
            TeslaBleConstants.TAG_COUNTER to
                TeslaCrypto.uint32ToBytes(session.counter),
            TeslaBleConstants.TAG_FLAGS to
                TeslaCrypto.uint32ToBytes(0),
        )
        val aad = TeslaCrypto.encodeTlv(tlvEntries)

        // AES-GCM 加密
        val (ciphertext, tag) = TeslaCrypto.aesGcmEncrypt(
            key = session.sharedKey,
            nonce = nonce,
            plaintext = payload,
            aad = aad,
        )

        // 构建签名数据
        val signatureData = TeslaBleMessages.encodeSignatureData(
            epoch = session.epoch,
            nonce = nonce,
            counter = session.counter,
            expiresAt = expiresAt,
            tag = tag,
        )

        // 组装并发送 RoutableMessage
        val message = TeslaBleMessages.buildSignedCommandMessage(
            ciphertext = ciphertext,
            signatureData = signatureData,
            domain = domain,
        )

        bleManager.sendMessage(message)
    }

    /**
     * 轮询车辆状态
     *
     * 完整流程:
     * 1. 加载已配对密钥
     * 2. 扫描并连接车辆 BLE
     * 3. VCSEC 域 ECDH 握手 → 发送唤醒命令
     * 4. Infotainment 域 ECDH 握手 → 发送 GetVehicleState 请求
     * 5. 接收并解密 Infotainment 响应
     * 6. 解析 VehicleState (车速/位置/航向/海拔/电池/温度/里程表)
     * 7. 单位换算 (mph→km/h, mi→km)
     * 8. 计算导出数据 (加速度/行程里程/G力)
     * 9. 返回完整 VehicleData
     *
     * @param vin 车辆识别号
     * @return 车辆数据，失败返回 null
     */
    private suspend fun pollVehicleState(vin: String): VehicleData? {
        // 加载密钥
        val privateKey = keyManager.loadPrivateKey() ?: return null
        val publicKeyRaw = keyManager.loadPublicKeyRaw() ?: return null

        try {
            // 1. 扫描并连接
            val device = withTimeoutOrNull(10000L) {
                bleManager.scanForVehicle(vin, timeoutMs = 10000L)
            } ?: return null

            bleManager.connect(device, timeoutMs = 15000L)

            // 2. VCSEC 域握手 → 唤醒车辆
            val vcsecSession = performHandshake(privateKey, publicKeyRaw, TeslaBleConstants.DOMAIN_VEHICLE_SECURITY)
            session = vcsecSession

            val wakePayload = TeslaBleMessages.encodeRkeAction(TeslaBleConstants.RKE_ACTION_WAKE_VEHICLE)
            sendSignedCommand(vcsecSession, wakePayload, TeslaBleConstants.DOMAIN_VEHICLE_SECURITY)
            Log.d(TAG, "Wake command sent via VCSEC")

            // 等待车辆唤醒
            delay(1000)

            // 3. Infotainment 域握手 → 查询车辆状态
            val infotainmentSession = performHandshake(privateKey, publicKeyRaw, TeslaBleConstants.DOMAIN_INFOTAINMENT)
            Log.d(TAG, "Infotainment handshake completed, counter=${infotainmentSession.counter}")

            // 4. 发送 GetVehicleState 请求
            val getStatePayload = TeslaBleMessages.encodeGetVehicleState()
            sendSignedCommand(infotainmentSession, getStatePayload, TeslaBleConstants.DOMAIN_INFOTAINMENT)

            // 5. 接收 Infotainment 响应
            val responseBytes = withTimeoutOrNull(TeslaBleConstants.COMMAND_TIMEOUT_MS) {
                bleManager.receiveMessage()
            } ?: run {
                Log.w(TAG, "Infotainment response timeout")
                return VehicleData(isTeslaConnected = true)
            }

            // 6. 解析 RoutableMessage 并解密
            val response = TeslaBleMessages.parseRoutableMessage(responseBytes)
            val plaintext = decryptResponse(response, infotainmentSession, TeslaBleConstants.DOMAIN_INFOTAINMENT)

            if (plaintext == null) {
                Log.w(TAG, "Failed to decrypt Infotainment response")
                return VehicleData(isTeslaConnected = true)
            }

            // 7. 解析 VehicleState
            val stateData = TeslaBleMessages.parseVehicleStateResponse(plaintext)
            Log.d(TAG, "VehicleState parsed: speed=${stateData.speedMph}mph, soc=${stateData.batterySOC}%, " +
                    "gear=${stateData.gear}, odometer=${stateData.odometer}, insideTemp=${stateData.insideTemp}")

            // 8. 单位换算 + 导出数据计算
            return buildEnrichedVehicleData(stateData)

        } finally {
            disconnectSession()
        }
    }

    /**
     * 将 carserver VehicleStateData 转换为 VehicleData 并计算导出数据
     *
     * 执行以下处理:
     * 1. 单位换算: mph → km/h, mi → km (根据 gui_distance_units 判断)
     * 2. 纵向加速度: 由车速变化率计算 (Δv / Δt, m/s²)
     * 3. 横向加速度: 由航向变化率 × 车速计算 (v × ω, m/s²)
     * 4. 合成 G 力: √(纵向² + 横向²) / 9.81
     * 5. 行程里程: 由里程表差值累加
     * 6. 更新状态追踪变量 (prevSpeed/prevHeading/prevOdometer/prevTimestamp)
     *
     * @param stateData 从 BLE Infotainment 域解析的原始车辆状态
     * @return 包含所有字段的 VehicleData
     */
    private fun buildEnrichedVehicleData(stateData: TeslaBleMessages.VehicleStateData): VehicleData {
        val now = System.currentTimeMillis()

        // ===== 单位换算 =====
        val useKm = stateData.distanceUnits?.equals("km", ignoreCase = true) ?: true
        val milesToKm = 1.609344f

        // 车速: mph → km/h (Tesla API 统一返回 mph)
        val speedKmh = stateData.speedMph?.let { it * milesToKm } ?: 0f

        // 里程表: 根据距离单位判断是否需要换算
        val odometerKm = stateData.odometer?.let {
            if (useKm) it else it * milesToKm
        }

        // 续航里程: 同样根据距离单位换算
        val batteryRangeKm = stateData.batteryRange?.let {
            if (useKm) it else it * milesToKm
        }

        // ===== 导出数据计算 =====
        var accelLongitudinal = 0f
        var accelLateral = 0f
        var gForce: Float
        var tripDistance = accumulatedTripDistanceKm

        // 纵向加速度: (当前车速 - 上次车速) / 时间差
        if (prevSpeedKmh != null && prevTimestampMs > 0L) {
            val deltaSpeedMs = (speedKmh - prevSpeedKmh!!) * 1000f / 3600f
            val deltaT = (now - prevTimestampMs) / 1000f
            if (deltaT > 0f && deltaT < 30f) { // 合理时间窗口 (1-30秒)
                accelLongitudinal = deltaSpeedMs / deltaT
            }
        }

        // 横向加速度: 车速 × 航向变化率
        val heading = stateData.heading
        if (prevHeading != null && heading != null && prevTimestampMs > 0L) {
            val deltaT = (now - prevTimestampMs) / 1000f
            if (deltaT > 0f && deltaT < 30f) {
                // 航向角差值归一化到 -180~180
                var deltaHeadingDeg = heading - prevHeading!!
                if (deltaHeadingDeg > 180f) deltaHeadingDeg -= 360f
                if (deltaHeadingDeg < -180f) deltaHeadingDeg += 360f
                val deltaHeadingRad = deltaHeadingDeg * Math.PI.toFloat() / 180f
                val headingRate = deltaHeadingRad / deltaT // rad/s
                val speedMs = speedKmh * 1000f / 3600f
                accelLateral = speedMs * headingRate
            }
        }

        // 合成 G 力
        gForce = sqrt(accelLongitudinal * accelLongitudinal + accelLateral * accelLateral) / GRAVITY_MS2

        // 行程里程: 里程表差值累加
        if (odometerKm != null) {
            if (!tripStarted) {
                // 首次收到有效里程表，记录起始值
                tripStarted = true
                accumulatedTripDistanceKm = 0f
                tripDistance = 0f
            } else if (prevOdometerKm != null) {
                val delta = odometerKm - prevOdometerKm!!
                if (delta > 0f && delta < 100f) { // 合理里程增量 (< 100km/10s)
                    accumulatedTripDistanceKm += delta
                    tripDistance = accumulatedTripDistanceKm
                }
            }
            prevOdometerKm = odometerKm
        }

        // ===== 更新状态追踪变量 =====
        prevSpeedKmh = speedKmh
        if (heading != null) prevHeading = heading
        prevTimestampMs = now

        return VehicleData(
            // BLE 实时数据
            speed = speedKmh,
            latitude = stateData.latitude ?: 0.0,
            longitude = stateData.longitude ?: 0.0,
            heading = stateData.heading ?: 0f,
            altitude = stateData.altitude ?: 0.0,
            tripDistance = tripDistance,
            accelLongitudinal = accelLongitudinal,
            accelLateral = accelLateral,
            gForce = gForce,
            // BLE 车辆状态
            batterySOC = stateData.batterySOC,
            batteryRange = batteryRangeKm,
            insideTemp = stateData.insideTemp,
            outsideTemp = stateData.outsideTemp,
            gear = stateData.gear,
            odometer = odometerKm,
            // 门/舱/锁状态
            isLocked = stateData.isLocked,
            df = stateData.df,
            dr = stateData.dr,
            pf = stateData.pf,
            pr = stateData.pr,
            ft = stateData.ft,
            rt = stateData.rt,
            // 状态
            isTeslaConnected = true,
            // 内部追踪 (供电耗计算用)
            prevOdometer = prevOdometerKm,
            prevSpeed = prevSpeedKmh,
            prevTimestamp = prevTimestampMs,
        )
    }

    /**
     * 解密 RoutableMessage 响应
     *
     * 通用解密方法，适用于 VCSEC 和 Infotainment 两个 Domain 的加密响应。
     * 使用响应签名数据中的 nonce/tag/counter/epoch 进行 AES-GCM 解密。
     *
     * @param response RoutableMessage 响应
     * @param session 当前会话 (用于获取共享密钥)
     * @param domain 响应来源 Domain (影响 AAD 构造)
     * @return 解密后的 plaintext，解密失败返回 null
     */
    private fun decryptResponse(
        response: TeslaBleMessages.RoutableMessageResponse,
        session: BleSession,
        domain: Int,
    ): ByteArray? {
        val payload = response.payload
        if (payload == null || payload.isEmpty()) return null

        try {
            val sigData = response.signatureData ?: return null
            val sigFields = TeslaProtobuf.parseAllFields(sigData)
            val apsBytes = TeslaProtobuf.getBytes(sigFields, TeslaBleConstants.FIELD_SD_AES_GCM_PERSONALIZED)
                ?: return null

            val apsFields = TeslaProtobuf.parseAllFields(apsBytes)
            val respEpoch = TeslaProtobuf.getBytes(apsFields, TeslaBleConstants.FIELD_APS_EPOCH)
                ?: session.epoch
            val respNonce = TeslaProtobuf.getBytes(apsFields, TeslaBleConstants.FIELD_APS_NONCE)
                ?: ByteArray(0)
            val respCounter = TeslaProtobuf.getUint32(apsFields, TeslaBleConstants.FIELD_APS_COUNTER)
                ?: session.counter
            val respTag = TeslaProtobuf.getBytes(apsFields, TeslaBleConstants.FIELD_APS_TAG)
                ?: ByteArray(0)

            // 构造解密用 AAD (使用响应来源 Domain)
            val respTlvEntries = listOf(
                TeslaBleConstants.TAG_SIGNATURE_TYPE to
                    TeslaCrypto.uint32ToBytes(TeslaBleConstants.SIGNATURE_TYPE_AES_GCM_RESPONSE),
                TeslaBleConstants.TAG_DOMAIN to
                    TeslaCrypto.uint32ToBytes(domain),
                TeslaBleConstants.TAG_PERSONALIZATION to
                    (vin?.toByteArray() ?: ByteArray(0)),
                TeslaBleConstants.TAG_EPOCH to respEpoch,
                TeslaBleConstants.TAG_COUNTER to
                    TeslaCrypto.uint32ToBytes(respCounter),
            )
            val respAad = TeslaCrypto.encodeTlv(respTlvEntries)

            // AES-GCM 解密
            return TeslaCrypto.aesGcmDecrypt(
                key = session.sharedKey,
                nonce = respNonce,
                ciphertext = payload,
                tag = respTag,
                aad = respAad,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt response (domain=$domain): ${e.message}")
            return null
        }
    }

    /**
     * 解析 VCSEC 响应消息
     *
     * VCSEC 返回的 UnsignedMessage 可能包含:
     * - VehicleStatus (车辆安全状态: 锁/车门/后备箱)
     * - WhitelistInfo (钥匙白名单信息)
     * - CommandStatus (命令执行状态)
     *
     * 注意: 车速/位置/电池/温度等车辆运行数据通过 Infotainment 域获取，
     * 不在 VCSEC 响应中。此方法保留供后续安全状态查询使用。
     */
    private fun parseVcsecResponse(plaintext: ByteArray): VehicleData {
        val fields = TeslaProtobuf.parseAllFields(plaintext)

        // VCSEC 响应中可能包含的字段:
        // - InformationRequest 响应 (field 1: InformationResponse)
        // - CommandStatus (命令执行状态)
        // - VehicleStatus (车辆状态)

        // TODO: 根据实际 VCSEC 响应格式解析更多字段
        // 目前返回已连接状态，具体字段解析需要根据实际协议测试完善

        // 从 InformationResponse 中尝试提取数据
        TeslaProtobuf.getBytes(fields, 1) // InformationResponse

        return VehicleData(
            isTeslaConnected = true,
            // 以下字段需要根据实际 VCSEC 响应格式解析
            // batterySOC, batteryRange, insideTemp, outsideTemp, gear, odometer
            // 这些字段主要通过 Infotainment 域的 carserver 获取
        )
    }

    // ===== 辅助方法 =====

    /**
     * 断开当前 BLE 会话
     */
    private fun disconnectSession() {
        bleManager.disconnect()
        session = null
    }

    /**
     * 测试 BLE 连接
     *
     * 尝试扫描并连接车辆，验证配对是否有效。
     *
     * @param vin 车辆识别号
     * @return 连接是否成功
     */
    suspend fun testConnection(vin: String): Boolean {
        val privateKey = keyManager.loadPrivateKey() ?: return false
        val publicKeyRaw = keyManager.loadPublicKeyRaw() ?: return false

        return try {
            val device = withTimeoutOrNull(10000L) {
                bleManager.scanForVehicle(vin, timeoutMs = 10000L)
            } ?: return false

            bleManager.connect(device, timeoutMs = 15000L)
            performHandshake(privateKey, publicKeyRaw)
            disconnectSession()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Connection test failed: ${e.message}")
            disconnectSession()
            false
        }
    }

    // ===== 内部类 =====

    /**
     * BLE 加密会话
     *
     * @param sharedKey AES-128 共享密钥 (16 字节)
     * @param epoch 16 字节 epoch ID
     * @param counter 反重放计数器
     * @param vehiclePublicKey 车辆公钥
     */
    data class BleSession(
        var sharedKey: ByteArray,
        var epoch: ByteArray,
        var counter: Int,
        var vehiclePublicKey: PublicKey,
    )

    /**
     * 配对状态密封类
     */
    sealed class PairingState {
        /** 空闲 */
        object Idle : PairingState()
        /** 正在生成密钥 */
        object GeneratingKey : PairingState()
        /** 正在扫描车辆 */
        object Scanning : PairingState()
        /** 正在连接 */
        object Connecting : PairingState()
        /** 正在握手 */
        object Handshaking : PairingState()
        /** 正在发送配对请求 */
        object SendingPairRequest : PairingState()
        /** 等待 NFC 卡片确认 */
        object WaitingForNfcConfirmation : PairingState()
        /** 正在保存密钥 */
        object SavingKey : PairingState()
        /** 配对完成 */
        object Completed : PairingState()
        /** 配对失败 */
        data class Failed(val message: String) : PairingState()
    }

    companion object {
        /** BLE 轮询间隔 (毫秒) — BLE 通信低延迟，可较频繁 */
        private const val POLL_INTERVAL_MS = 10_000L

        /** 重力加速度 m/s² (用于 G 力计算) */
        private const val GRAVITY_MS2 = 9.81f
    }
}
