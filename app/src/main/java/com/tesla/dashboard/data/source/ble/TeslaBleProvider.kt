package com.tesla.dashboard.data.source.ble

import android.util.Log
import com.tesla.dashboard.data.model.VehicleData
import com.tesla.dashboard.data.source.VehicleDataSource
import com.tesla.dashboard.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.PrivateKey
import java.security.PublicKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
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

    /**
     * BLE 会话互斥锁 (v0.5.0)
     *
     * 轮询与车辆控制命令都依赖"连接→握手→收发→断开"整段会话流程,
     * 若不串行化, 轮询结束时的 disconnectSession() 可能打断
     * 控制命令正在进行的收发, 反之亦然。
     */
    private val sessionMutex = Mutex()

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

    // ===== v0.4.2: 行驶状态追踪 (自适应轮询间隔) =====

    /** 最近一次档位 (用于判断行驶状态) */
    @Volatile
    private var lastGear: String? = null

    /** 最近一次车速 km/h */
    @Volatile
    private var lastSpeedKmh: Float = 0f

    /** 最近一次瞬时功率 kW (null=未知) */
    @Volatile
    private var lastPowerKw: Float? = null

    // ===== v0.4.2: 数据失效保护 + 退避重连 =====

    /** 最近一次成功轮询的完整数据 (失败时保留展示) */
    @Volatile
    private var lastGoodData: VehicleData? = null

    /** 连续轮询失败次数 (用于指数退避) */
    @Volatile
    private var consecutiveFailures: Int = 0

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
                    consecutiveFailures = 0
                    lastGoodData = vehicleData
                    _isAvailable.value = true
                    emit(vehicleData)
                } else {
                    emit(buildPollFailureData())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Poll error: ${e.message}")
                Log.w(TAG, "Poll error: ${e.message}")
                emit(buildPollFailureData())
            }

            // v0.4.2: 自适应间隔 — 行驶中加快轮询 (2.5s), 静止 5s;
            // 连续失败时指数退避 (10s→20s→30s 封顶), 避免无效空转耗电
            delay(nextPollDelayMs())
        }
    }

    /**
     * 轮询失败时的数据 (v0.4.2 数据失效保护)
     *
     * 保留上次成功值继续展示并标记 [VehicleData.isDataStale],
     * 避免失败时 UI 数值清空归零闪烁。
     */
    private fun buildPollFailureData(): VehicleData {
        consecutiveFailures++
        _isAvailable.value = false
        return lastGoodData?.copy(
            isTeslaConnected = false,
            isDataStale = true,
        ) ?: VehicleData(isTeslaConnected = false)
    }

    /**
     * 计算下一次轮询延迟 (v0.4.2)
     *
     * - 最近一次轮询成功: 行驶中 [POLL_INTERVAL_DRIVING_MS], 静止 [POLL_INTERVAL_MS]
     * - 连续失败: 指数退避, 封顶 [MAX_BACKOFF_MS]
     */
    private fun nextPollDelayMs(): Long {
        if (consecutiveFailures == 0) {
            return if (isDriving()) POLL_INTERVAL_DRIVING_MS else POLL_INTERVAL_MS
        }
        val shift = (consecutiveFailures - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        return (POLL_INTERVAL_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * 判断车辆是否处于行驶状态 (v0.4.2)
     *
     * 任一条件满足即视为行驶中:
     * 1. 车速 > [DRIVING_SPEED_THRESHOLD_KMH]
     * 2. 档位为 D/R
     * 3. 功率绝对值 > [DRIVING_POWER_THRESHOLD_KW] (驱动/回收扭矩)
     */
    private fun isDriving(): Boolean =
        lastSpeedKmh > DRIVING_SPEED_THRESHOLD_KMH ||
            lastGear == "D" || lastGear == "R" ||
            (lastPowerKw != null && abs(lastPowerKw!!) > DRIVING_POWER_THRESHOLD_KW)

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
     * @param scanTimeoutMs 扫描/连接超时(毫秒),默认 20s (车辆深睡时广播可能延迟)
     * @param nfcTimeoutMs 等待 NFC 确认超时(毫秒),默认 [TeslaBleConstants.NFC_CONFIRM_TIMEOUT_MS] = 30s
     * @return 配对是否成功
     */
    suspend fun startPairing(
        vin: String,
        scanTimeoutMs: Long = 20000L,
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
                bleManager.scanForVehicle(vin, timeoutMs = scanTimeoutMs)
            } ?: run {
                _pairingState.value = PairingState.Failed(
                    "未发现车辆广播。请确认: 1) 已解锁车门/踩下刹车唤醒车辆 2) 手机靠近中控台 3) 车辆电量正常"
                )
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
            // 固定车辆 VCSEC 公钥, 后续握手校验防止中继/伪装 (v0.4.1 安全加固)
            keyManager.saveVehiclePublicKeyRaw(TeslaCrypto.encodePublicKey(tempSession.vehiclePublicKey))
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
        bleManager.clearDeviceCache()
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

        // 发送并等待响应 (v0.4.2: 超时重发 1 次 — SessionInfoRequest 无状态, 重发安全)
        val responseBytes = bleManager.sendAndWait(handshakeMsg, retries = HANDSHAKE_RETRY_COUNT)

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
     * 发送已签名命令并等待响应 (v0.4.2 支持超时重发)
     *
     * 超时后重发同一命令 — 每次重发都会递增 counter 并重新签名,
     * 车辆按单调递增校验 counter, 因此无论首包是否到达, 重发均安全。
     *
     * @param session 当前 BLE 会话
     * @param payload 命令 payload (protobuf 编码)
     * @param domain 目标 Domain
     * @param timeoutMs 单次响应超时
     * @param retries 超时后的重发次数
     * @return 响应消息, 全部尝试超时返回 null
     */
    private suspend fun sendSignedCommandAndWait(
        session: BleSession,
        payload: ByteArray,
        domain: Int,
        timeoutMs: Long = TeslaBleConstants.COMMAND_TIMEOUT_MS,
        retries: Int = 0,
    ): ByteArray? {
        var attempt = 0
        while (true) {
            sendSignedCommand(session, payload, domain)
            val response = withTimeoutOrNull(timeoutMs) {
                bleManager.receiveMessage(timeoutMs)
            }
            if (response != null) return response
            if (attempt >= retries) return null
            attempt++
            AppLog.w(TAG, "Signed command response timeout (attempt ${attempt + 1}/$retries), resending")
        }
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
    private suspend fun pollVehicleState(vin: String): VehicleData? = sessionMutex.withLock {
        // 加载密钥
        val privateKey = keyManager.loadPrivateKey() ?: run {
            AppLog.w(TAG, "Poll skipped: private key not available")
            return@withLock null
        }
        val publicKeyRaw = keyManager.loadPublicKeyRaw() ?: return@withLock null

        try {
            // 1. 优先使用缓存的设备地址直接连接 (v0.4 优化, 免扫描),
            //    失败(地址失效/车辆换机等)时回退到全量扫描
            val connected = bleManager.tryConnectCached(timeoutMs = 15000L)
            if (!connected) {
                val device = withTimeoutOrNull(10000L) {
                    bleManager.scanForVehicle(vin, timeoutMs = 10000L)
                } ?: run {
                    AppLog.w(TAG, "Scan fallback failed: vehicle not found (within 10s)")
                    return@withLock null
                }
                bleManager.connect(device, timeoutMs = 15000L)
            }

            // 2. VCSEC 域握手 → 唤醒车辆
            val vcsecSession = performHandshake(privateKey, publicKeyRaw, TeslaBleConstants.DOMAIN_VEHICLE_SECURITY)
            // 车辆公钥固定校验: 与配对时固定的公钥不符 → 拒绝连接 (防中继/伪装)
            val pinnedVehicleKey = keyManager.loadVehiclePublicKeyRaw()
            if (pinnedVehicleKey != null &&
                !pinnedVehicleKey.contentEquals(TeslaCrypto.encodePublicKey(vcsecSession.vehiclePublicKey))
            ) {
                AppLog.e(TAG, "Vehicle public key MISMATCH vs pinned - aborting (possible relay/MITM)")
                return@withLock null
            }
            session = vcsecSession

            val wakePayload = TeslaBleMessages.encodeRkeAction(TeslaBleConstants.RKE_ACTION_WAKE_VEHICLE)
            sendSignedCommand(vcsecSession, wakePayload, TeslaBleConstants.DOMAIN_VEHICLE_SECURITY)
            Log.d(TAG, "Wake command sent via VCSEC")

            // 等待车辆唤醒
            delay(1000)

            // 3. Infotainment 域握手 → 查询车辆状态
            val infotainmentSession = performHandshake(privateKey, publicKeyRaw, TeslaBleConstants.DOMAIN_INFOTAINMENT)
            Log.d(TAG, "Infotainment handshake completed, counter=${infotainmentSession.counter}")

            // 4. 发送 GetVehicleState 请求并等待响应 (v0.4.2: 超时重发 1 次,
            //    每次重发使用新递增 counter, 车辆按单调递增校验, 重发安全)
            val getStatePayload = TeslaBleMessages.encodeGetVehicleState()
            val responseBytes = sendSignedCommandAndWait(
                infotainmentSession,
                getStatePayload,
                TeslaBleConstants.DOMAIN_INFOTAINMENT,
                retries = COMMAND_RETRY_COUNT,
            )
            if (responseBytes == null) {
                AppLog.w(TAG, "Infotainment response timeout after retries")
                Log.w(TAG, "Infotainment response timeout after retries")
                return@withLock VehicleData(isTeslaConnected = true)
            }

            // 6. 解析 RoutableMessage 并解密
            val response = TeslaBleMessages.parseRoutableMessage(responseBytes)
            val plaintext = decryptResponse(response, infotainmentSession, TeslaBleConstants.DOMAIN_INFOTAINMENT)

            if (plaintext == null) {
                AppLog.w(TAG, "Failed to decrypt Infotainment response")
                Log.w(TAG, "Failed to decrypt Infotainment response")
                return@withLock VehicleData(isTeslaConnected = true)
            }

            // 7. 解析 VehicleState
            val stateData = TeslaBleMessages.parseVehicleStateResponse(plaintext)
            Log.d(TAG, "VehicleState parsed: speed=${stateData.speedMph}mph, soc=${stateData.batterySOC}%, " +
                    "gear=${stateData.gear}, odometer=${stateData.odometer}, insideTemp=${stateData.insideTemp}")

            // 8. 单位换算 + 导出数据计算
            return@withLock buildEnrichedVehicleData(stateData)

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

        // v0.4.2: 行驶状态追踪 (自适应轮询间隔用)
        lastGear = stateData.gear
        lastSpeedKmh = speedKmh
        lastPowerKw = stateData.powerKw

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
            powerKw = stateData.powerKw,
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

    // ===== 车辆控制命令 (v0.5.0) =====

    /**
     * 车辆控制命令类型
     */
    sealed class VehicleCommand {
        /** 解锁车辆 */
        object Unlock : VehicleCommand()
        /** 锁定车辆 */
        object Lock : VehicleCommand()
        /** 打开前备箱 */
        object OpenFrunk : VehicleCommand()
        /** 打开后备箱 */
        object OpenTrunk : VehicleCommand()
    }

    /**
     * 发送车辆控制命令 (解锁/闭锁/前后备箱) (v0.5.0)
     *
     * 流程:
     * 1. 优先缓存地址直连, 失败回退全量扫描
     * 2. VCSEC 域 ECDH 握手 + 车辆公钥固定校验
     * 3. 发送唤醒命令 (WakeVehicle)
     * 4. 发送控制命令 (RKEAction / ClosureMoveRequest) 并等待响应
     * 5. 解密响应, 解析 CommandStatus.operation_status
     *
     * @param command 控制命令
     * @return true=车辆确认执行成功 (operation_status == SUCCESS);
     *         响应无法解密/未收到时按"已发送"返回 true (兼容性宽松);
     *         false=连接/握手/公钥校验失败
     */
    suspend fun sendVehicleCommand(command: VehicleCommand): Boolean = sessionMutex.withLock {
        val currentVin = vin
        if (currentVin.isNullOrBlank()) return@withLock false
        val privateKey = keyManager.loadPrivateKey() ?: return@withLock false
        val publicKeyRaw = keyManager.loadPublicKeyRaw() ?: return@withLock false

        try {
            // 1. 连接 (优先缓存直连, 失败回退扫描)
            val connected = bleManager.tryConnectCached(timeoutMs = 15000L)
            if (!connected) {
                val device = withTimeoutOrNull(10000L) {
                    bleManager.scanForVehicle(currentVin, timeoutMs = 10000L)
                } ?: run {
                    AppLog.w(TAG, "Command $command failed: vehicle not found")
                    return@withLock false
                }
                bleManager.connect(device, timeoutMs = 15000L)
            }

            // 2. VCSEC 域握手 + 公钥固定校验
            val vcsecSession = performHandshake(privateKey, publicKeyRaw, TeslaBleConstants.DOMAIN_VEHICLE_SECURITY)
            val pinnedVehicleKey = keyManager.loadVehiclePublicKeyRaw()
            if (pinnedVehicleKey != null &&
                !pinnedVehicleKey.contentEquals(TeslaCrypto.encodePublicKey(vcsecSession.vehiclePublicKey))
            ) {
                AppLog.e(TAG, "Command $command aborted: vehicle public key MISMATCH vs pinned")
                return@withLock false
            }
            session = vcsecSession

            // 3. 唤醒车辆 (RKE 唤醒命令, 车辆深睡时也能唤醒; 等待唤醒稳定)
            sendSignedCommand(
                vcsecSession,
                TeslaBleMessages.encodeRkeAction(TeslaBleConstants.RKE_ACTION_WAKE_VEHICLE),
                TeslaBleConstants.DOMAIN_VEHICLE_SECURITY,
            )
            delay(500)

            // 4. 构建并发送控制命令
            val payload = when (command) {
                VehicleCommand.Unlock -> TeslaBleMessages.encodeRkeAction(TeslaBleConstants.RKE_ACTION_UNLOCK)
                VehicleCommand.Lock -> TeslaBleMessages.encodeRkeAction(TeslaBleConstants.RKE_ACTION_LOCK)
                VehicleCommand.OpenFrunk -> TeslaBleMessages.encodeClosureMoveRequest(
                    TeslaBleConstants.CLOSURE_FRUNK,
                    TeslaBleConstants.CLOSURE_ACTION_OPEN,
                )
                VehicleCommand.OpenTrunk -> TeslaBleMessages.encodeClosureMoveRequest(
                    TeslaBleConstants.CLOSURE_TRUNK,
                    TeslaBleConstants.CLOSURE_ACTION_OPEN,
                )
            }
            val response = sendSignedCommandAndWait(
                vcsecSession,
                payload,
                TeslaBleConstants.DOMAIN_VEHICLE_SECURITY,
                retries = COMMAND_RETRY_COUNT,
            ) ?: run {
                AppLog.w(TAG, "Command $command: no response (may still have executed)")
                return@withLock true
            }

            // 5. 解密并解析 CommandStatus
            val responseMsg = TeslaBleMessages.parseRoutableMessage(response)
            val plaintext = decryptResponse(responseMsg, vcsecSession, TeslaBleConstants.DOMAIN_VEHICLE_SECURITY)
            val status = plaintext?.let { TeslaBleMessages.parseCommandStatus(it) }
            AppLog.d(TAG, "Command $command status=$status")
            // SUCCESS 或无法解析时均视为成功 (未收到失败确认)
            status == null || status == TeslaBleConstants.OP_STATUS_SUCCESS

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Command $command failed: ${e.message}")
            false
        } finally {
            disconnectSession()
        }
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
            val vcsecSession = performHandshake(privateKey, publicKeyRaw)
            // 车辆公钥固定校验 (与配对时固定值一致才视为同一辆车)
            val pinnedVehicleKey = keyManager.loadVehiclePublicKeyRaw()
            if (pinnedVehicleKey != null &&
                !pinnedVehicleKey.contentEquals(TeslaCrypto.encodePublicKey(vcsecSession.vehiclePublicKey))
            ) {
                AppLog.e(TAG, "Connection test: vehicle public key MISMATCH vs pinned")
                disconnectSession()
                return false
            }
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
        /** BLE 轮询间隔 (毫秒) — v0.4: 10s → 5s, 配合缓存直连提升仪表响应 */
        private const val POLL_INTERVAL_MS = 5_000L

        /** 行驶中轮询间隔 (毫秒) — v0.4.2: 行驶状态数据更密集 */
        private const val POLL_INTERVAL_DRIVING_MS = 2_500L

        /** 连续失败退避封顶 (毫秒) — v0.4.2: 避免无效空转耗电 */
        private const val MAX_BACKOFF_MS = 30_000L

        /** 退避最大移位次数 (5s → 10s → 20s → 30s 封顶) */
        private const val MAX_BACKOFF_SHIFT = 2

        /** 判定"行驶中"的车速阈值 km/h */
        private const val DRIVING_SPEED_THRESHOLD_KMH = 1f

        /** 判定"行驶中"的功率阈值 kW (排除空调等静态负载) */
        private const val DRIVING_POWER_THRESHOLD_KW = 2f

        /** 握手超时重发次数 */
        private const val HANDSHAKE_RETRY_COUNT = 1

        /** GetVehicleState 超时重发次数 */
        private const val COMMAND_RETRY_COUNT = 1

        /** 重力加速度 m/s² (用于 G 力计算) */
        private const val GRAVITY_MS2 = 9.81f
    }
}
