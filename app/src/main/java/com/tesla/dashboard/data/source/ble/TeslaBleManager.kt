package com.tesla.dashboard.data.source.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.tesla.dashboard.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tesla BLE GATT 通信管理器
 *
 * 负责:
 * - BLE 扫描 (按 VIN 计算的 Local Name 过滤)
 * - GATT 连接/断开
 * - 服务/特征发现
 * - MTU 协商
 * - 消息分帧 (2 字节长度前缀 + 分块传输)
 * - 消息发送/接收 (异步回调转协程)
 *
 * 通信流程:
 * 1. [scanForVehicle] 扫描车辆 BLE 广播
 * 2. [connect] 建立 GATT 连接
 * 3. [sendMessage] 发送消息 (自动分帧)
 * 4. [receiveMessage] 等待接收消息 (通过 Notify 回调)
 * 5. [disconnect] 断开连接
 *
 * 基于 Tesla vehicle-command SDK 的 pkg/connector/ble/ble.go 实现。
 *
 * @param context 应用级 Context
 */
@Singleton
class TeslaBleManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val TAG = "TeslaBleManager"

    /** Bluetooth Adapter (懒加载) */
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    /** 当前 GATT 连接 */
    @Volatile
    private var gatt: BluetoothGatt? = null

    /** 当前连接的设备 */
    @Volatile
    private var connectedDevice: BluetoothDevice? = null

    /**
     * 最近一次成功连接的设备地址缓存 (v0.4 优化)
     *
     * 轮询时优先使用缓存地址直接连接, 免去每次 10s 全量扫描,
     * 显著缩短单次轮询耗时与功耗。
     */
    @Volatile
    private var lastDeviceAddress: String? = null

    /** TX 特征 (写入) */
    @Volatile
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    /** RX 特征 (读取/通知) */
    @Volatile
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    /** 当前 MTU 大小 */
    @Volatile
    private var currentMtu: Int = 23 // 默认 BLE MTU

    /** 接收缓冲区 */
    private val rxBuffer = ByteArrayOutputStream()

    /** 接收消息队列 (异步回调转协程) */
    private val messageQueue = ConcurrentLinkedQueue<CompletableDeferred<ByteArray>>()

    /** 连接状态回调 */
    private var connectionDeferred: CompletableDeferred<Boolean>? = null

    /** 扫描状态回调 */
    private var scanDeferred: CompletableDeferred<BluetoothDevice>? = null

    // ===== BLE 扫描 =====

    /**
     * 扫描 Tesla 车辆 BLE 广播
     *
     * 采用宽扫描 + 回调内匹配 (v0.4.1 修复):
     * 不再依赖系统按 Local Name 过滤 (部分固件广播名称拆分/截断会导致过滤失败),
     * 而是扫描全部设备, 在回调中匹配:
     * 1. 广播名称 (advData localName) 与期望名称一致 (不区分大小写), 或
     * 2. 广播中包含 Tesla 服务 UUID [TeslaBleConstants.SERVICE_UUID]
     *
     * 扫描期间记录所有可见设备, 超时失败时写入 AppLog 供导出诊断。
     *
     * @param vin 车辆识别号
     * @param timeoutMs 扫描超时 (毫秒)
     * @return 匹配的 BluetoothDevice
     * @throws TimeoutCancellationException 扫描超时
     * @throws IllegalStateException 蓝牙未开启或不支持
     */
    @SuppressLint("MissingPermission")
    suspend fun scanForVehicle(vin: String, timeoutMs: Long = 15000L): BluetoothDevice {
        val adapter = bluetoothAdapter
            ?: throw IllegalStateException("Bluetooth not supported")
        if (!adapter.isEnabled) {
            throw IllegalStateException("Bluetooth is disabled")
        }

        val localName = TeslaBleConstants.vehicleLocalName(vin)
        Log.i(TAG, "Scanning for Tesla vehicle, localName=$localName, VIN=$vin")

        val scanner = adapter.bluetoothLeScanner
            ?: throw IllegalStateException("BLE scanner not available")

        scanDeferred = CompletableDeferred()

        // 不设名称过滤, 宽扫描后回调内匹配 (v0.4.1)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // 诊断: 记录扫描期间看到的所有设备
        val seenDevices = LinkedHashMap<String, String>() // address -> name

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                // 记录设备 (仅首次) 供诊断
                if (!seenDevices.containsKey(address)) {
                    val name = result.scanRecord?.deviceName ?: result.device.name ?: "(unnamed)"
                    seenDevices[address] = name
                    Log.v(TAG, "Seen device: $name ($address)")
                }

                // 匹配: 广播名称 或 Service UUID
                val advName = result.scanRecord?.deviceName
                val nameMatch = advName != null && advName.equals(localName, ignoreCase = true)
                val uuidMatch = result.scanRecord?.serviceUuids?.any {
                    it.uuid.toString().uppercase() == TeslaBleConstants.SERVICE_UUID.uppercase()
                } == true
                val deviceNameMatch = result.device.name?.equals(localName, ignoreCase = true) == true

                if (nameMatch || uuidMatch || deviceNameMatch) {
                    Log.i(TAG, "Found Tesla vehicle: $address (name=$advName)")
                    lastDeviceAddress = address
                    scanDeferred?.complete(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: errorCode=$errorCode")
                scanDeferred?.completeExceptionally(
                    IllegalStateException("BLE scan failed: $errorCode")
                )
            }
        }

        scanner.startScan(listOf(), settings, scanCallback)

        try {
            return withTimeout(timeoutMs) {
                requireNotNull(scanDeferred).await()
            }
        } catch (e: TimeoutCancellationException) {
            // 诊断: 记录扫描期间所有可见设备, 帮助定位车辆广播异常
            val dump = seenDevices.entries.joinToString("; ") { "${it.value} <${it.key}>" }
            val summary = if (seenDevices.isEmpty()) "(no devices seen)" else dump
            AppLog.w(TAG, "Scan timeout, localName=$localName. Nearby devices: $summary")
            throw e
        } finally {
            scanner.stopScan(scanCallback)
        }
    }

    /**
     * 尝试通过缓存的设备地址直接连接 (v0.4 优化)
     *
     * 跳过 BLE 扫描阶段, 直接按上次成功连接的 MAC 地址连接 GATT,
     * 大幅缩短轮询耗时。失败(如地址失效/车辆换机)时返回 false,
     * 调用方应回退到 [scanForVehicle] 全量扫描。
     *
     * 注意: 使用较短超时 (6s) — 缓存地址直连失败说明地址可能已轮换
     * (Tesla 使用随机可解析 BLE 地址), 应尽快回退扫描。
     *
     * @param timeoutMs 连接超时 (毫秒)
     * @return 是否连接成功
     */
    @SuppressLint("MissingPermission")
    suspend fun tryConnectCached(timeoutMs: Long = 6000L): Boolean {
        val address = lastDeviceAddress ?: return false
        val adapter = bluetoothAdapter ?: return false
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            lastDeviceAddress = null
            return false
        }

        return try {
            connect(device, timeoutMs = timeoutMs)
            Log.i(TAG, "Direct connect via cached address: $address")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cached connect failed, fallback to scan: ${e.message}")
            lastDeviceAddress = null
            // 释放可能残留的半连接 GATT, 避免与后续扫描连接串扰
            disconnect()
            false
        }
    }

    // ===== GATT 连接 =====

    /**
     * 建立 GATT 连接
     *
     * @param device 目标蓝牙设备
     * @param timeoutMs 连接超时 (毫秒)
     * @throws TimeoutCancellationException 连接超时
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice, timeoutMs: Long = TeslaBleConstants.CONNECT_TIMEOUT_MS) {
        // 清理可能残留的旧 GATT 连接 (如上次直连超时留下的半连接),
        // 避免多个 GATT 并发导致旧连接回调串扰新连接的 deferred (v0.4.1 修复)
        disconnect()

        connectionDeferred = CompletableDeferred()

        val newGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
        gatt = newGatt

        try {
            withTimeout(timeoutMs) {
                requireNotNull(connectionDeferred).await()
            }
        } catch (e: TimeoutCancellationException) {
            AppLog.w(TAG, "GATT connect timeout to ${device.address}, timeout=${timeoutMs}ms")
            throw e
        }

        connectedDevice = device
        Log.i(TAG, "GATT connected to ${device.address}")
    }

    /**
     * 断开 GATT 连接
     *
     * 注意: [lastDeviceAddress] 缓存保留, 供下次轮询直接连接复用;
     * 仅设备地址变化或解绑时才由外部清除。
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        connectedDevice = null
        txCharacteristic = null
        rxCharacteristic = null
        rxBuffer.reset()
        messageQueue.clear()
        Log.i(TAG, "GATT disconnected")
    }

    /**
     * 清除缓存设备地址 (解绑/换车时调用, 避免直连旧车)
     */
    fun clearDeviceCache() {
        lastDeviceAddress = null
    }

    /**
     * 检查是否已连接
     */
    @SuppressLint("MissingPermission")
    fun isConnected(): Boolean = gatt?.let {
        try {
            it.device != null
        } catch (e: SecurityException) {
            false
        }
    } ?: false

    // ===== GATT 回调 =====

    /**
     * GATT 回调处理
     *
     * 处理连接状态变化、服务发现、特征读取、通知接收等事件。
     */
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // 忽略过期连接的回调 (v0.4.1: 直连超时后旧 GATT 可能仍在后台回调)
            if (gatt !== this@TeslaBleManager.gatt) return

            // v0.4.2: 断开原因诊断 — 非 0 状态码说明连接异常断开
            if (newState == BluetoothProfile.STATE_DISCONNECTED && status != BluetoothGatt.GATT_SUCCESS) {
                AppLog.e(
                    TAG,
                    "Unexpected GATT disconnect from ${gatt.device.address}: " +
                        "status=$status (${describeDisconnectStatus(status)})",
                )
            } else {
                Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // 连接成功，发起服务发现
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionDeferred?.complete(false)
                    Log.w(TAG, "GATT disconnected, status=$status")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== this@TeslaBleManager.gatt) return
            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionDeferred?.completeExceptionally(
                    IllegalStateException("Service discovery failed: $status")
                )
                return
            }

            // 查找 Tesla BLE 服务
            val service = gatt.getService(UUID.fromString(TeslaBleConstants.SERVICE_UUID))
            if (service == null) {
                connectionDeferred?.completeExceptionally(
                    IllegalStateException("Tesla BLE service not found")
                )
                return
            }

            // 获取 TX/RX 特征
            txCharacteristic = service.getCharacteristic(UUID.fromString(TeslaBleConstants.TX_CHARACTERISTIC_UUID))
            rxCharacteristic = service.getCharacteristic(UUID.fromString(TeslaBleConstants.RX_CHARACTERISTIC_UUID))

            if (txCharacteristic == null || rxCharacteristic == null) {
                connectionDeferred?.completeExceptionally(
                    IllegalStateException("TX/RX characteristics not found")
                )
                return
            }

            // 启用 RX Notify
            gatt.setCharacteristicNotification(rxCharacteristic, true)

            // 写入 CCCD 描述符以启用通知
            val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            val descriptor = rxCharacteristic!!.getDescriptor(cccdUuid)
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

            // 请求更大的 MTU
            gatt.requestMtu(517) // BLE 5.0 最大 MTU
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== this@TeslaBleManager.gatt) return
            Log.d(TAG, "onMtuChanged: mtu=$mtu, status=$status")
            currentMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            // MTU 协商完成，连接就绪
            connectionDeferred?.complete(true)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33", ReplaceWith("onCharacteristicChanged(gatt, characteristic, value)"))
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (gatt !== this@TeslaBleManager.gatt) return
            // 接收车辆返回的数据 (Notify)
            @Suppress("DEPRECATION")
            val data = characteristic.value
            handleReceivedData(data)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (gatt !== this@TeslaBleManager.gatt) return
            // 接收车辆返回的数据 (Notify) — API 33+ 新重载
            handleReceivedData(value)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (gatt !== this@TeslaBleManager.gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLog.w(TAG, "Characteristic write failed: status=$status (${describeDisconnectStatus(status)})")
            }
            Log.v(TAG, "onCharacteristicWrite: status=$status")
        }
    }

    /**
     * 解析 GATT 状态码为可读描述 (v0.4.2 诊断)
     *
     * @param status GATT 回调中的 status 码
     * @return 可读的原因描述
     */
    private fun describeDisconnectStatus(status: Int): String = when (status) {
        BluetoothGatt.GATT_SUCCESS -> "normal (remote disconnect)"
        0x08 -> "connection timeout"
        0x13 -> "remote terminated (link loss)"
        0x16 -> "local terminated"
        0x22 -> "remote user terminated"
        0x3E -> "unknown reason"
        0x85 -> "GATT client error"
        0x89 -> "authentication failure"
        0x8B -> "pin or key missing"
        0x100 -> "connection congestion"
        else -> "GATT code $status"
    }

    // ===== 消息收发 =====

    /**
     * 发送消息 (自动分帧)
     *
     * 消息格式: [2 字节大端长度] [protobuf 数据]
     * 分块大小: MTU - ATT_HEADER_SIZE
     *
     * @param message 完整的 protobuf 消息字节数组
     */
    @SuppressLint("MissingPermission")
    suspend fun sendMessage(message: ByteArray) {
        val tx = txCharacteristic
            ?: throw IllegalStateException("TX characteristic not available")
        val gatt = gatt ?: throw IllegalStateException("GATT not connected")

        // 构造带长度前缀的消息
        val framed = ByteArrayOutputStream()
        val lengthBytes = ByteBuffer.allocate(TeslaBleConstants.LENGTH_PREFIX_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(message.size.toShort())
            .array()
        framed.write(lengthBytes)
        framed.write(message)
        val framedData = framed.toByteArray()

        // 计算分块大小
        val blockLength = currentMtu - TeslaBleConstants.ATT_HEADER_SIZE
        var offset = 0

        Log.d(TAG, "Sending message: ${message.size} bytes, blockLength=$blockLength, mtu=$currentMtu")

        while (offset < framedData.size) {
            val end = minOf(offset + blockLength, framedData.size)
            val chunk = framedData.copyOfRange(offset, end)

            // 写入特征 (Write Without Response 用于分块传输)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(tx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                tx.value = chunk
                @Suppress("DEPRECATION")
                tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(tx)
            }

            offset = end
            // 短暂延迟避免 BLE 拥塞 (v0.4: delay 替代 Thread.sleep, 不阻塞调用线程)
            delay(5)
        }
    }

    /**
     * 等待接收消息
     *
     * 阻塞直到收到完整的 BLE 消息 (通过 Notify 回调)。
     *
     * @param timeoutMs 接收超时 (毫秒)
     * @return 接收到的完整消息 (不含长度前缀)
     * @throws TimeoutCancellationException 接收超时
     */
    suspend fun receiveMessage(timeoutMs: Long = TeslaBleConstants.COMMAND_TIMEOUT_MS): ByteArray {
        val deferred = CompletableDeferred<ByteArray>()
        messageQueue.add(deferred)

        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            messageQueue.remove(deferred)
            throw e
        }
    }

    /**
     * 发送消息并等待响应 (v0.4.2 支持超时重发)
     *
     * 响应超时后可选重发同一消息 (用于无状态请求, 如 SessionInfoRequest 握手),
     * 避免单次广播丢包导致整个轮询周期失败。
     *
     * @param message 要发送的消息
     * @param timeoutMs 响应超时 (毫秒)
     * @param retries 超时后的重发次数 (默认 0 = 不重发)
     * @return 响应消息
     */
    suspend fun sendAndWait(
        message: ByteArray,
        timeoutMs: Long = TeslaBleConstants.COMMAND_TIMEOUT_MS,
        retries: Int = 0,
    ): ByteArray {
        var attempt = 0
        while (true) {
            try {
                sendMessage(message)
                return receiveMessage(timeoutMs)
            } catch (e: TimeoutCancellationException) {
                if (attempt >= retries) throw e
                attempt++
                AppLog.w(TAG, "sendAndWait response timeout, retrying ($attempt/$retries)")
                delay(200)
            }
        }
    }

    // ===== 接收数据处理 =====

    /**
     * 处理接收到的 BLE 数据
     *
     * 将分块数据重组为完整消息，通过 messageQueue 通知等待的协程。
     *
     * @param data 接收到的数据块
     */
    private fun handleReceivedData(data: ByteArray) {
        if (data.isEmpty()) return

        rxBuffer.write(data)

        // 检查是否已收到完整消息
        val bufData = rxBuffer.toByteArray()
        if (bufData.size < TeslaBleConstants.LENGTH_PREFIX_SIZE) return

        // 读取消息长度 (2 字节大端)
        val expectedLength = ByteBuffer.wrap(bufData, 0, TeslaBleConstants.LENGTH_PREFIX_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .short
            .toInt() and 0xFFFF

        val totalSize = TeslaBleConstants.LENGTH_PREFIX_SIZE + expectedLength
        if (bufData.size < totalSize) return // 数据不完整，继续等待

        // 提取完整消息
        val message = bufData.copyOfRange(
            TeslaBleConstants.LENGTH_PREFIX_SIZE,
            TeslaBleConstants.LENGTH_PREFIX_SIZE + expectedLength
        )

        // 重置缓冲区 (保留多余数据)
        rxBuffer.reset()
        if (bufData.size > totalSize) {
            rxBuffer.write(bufData, totalSize, bufData.size - totalSize)
        }

        // 通知等待的协程
        val deferred = messageQueue.poll()
        deferred?.complete(message)
    }
}
