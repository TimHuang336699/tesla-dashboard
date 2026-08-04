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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
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
     * 根据车辆 VIN 计算广播名称，扫描匹配的设备。
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

        // 按名称过滤扫描
        val filter = ScanFilter.Builder()
            .setDeviceName(localName)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.i(TAG, "Found Tesla vehicle: ${result.device.address}")
                scanDeferred?.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: errorCode=$errorCode")
                scanDeferred?.completeExceptionally(
                    IllegalStateException("BLE scan failed: $errorCode")
                )
            }
        }

        scanner.startScan(listOf(filter), settings, scanCallback)

        try {
            return withTimeout(timeoutMs) {
                scanDeferred!!.await()
            }
        } finally {
            scanner.stopScan(scanCallback)
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
        connectionDeferred = CompletableDeferred()

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }

        withTimeout(timeoutMs) {
            connectionDeferred!!.await()
        }

        connectedDevice = device
        Log.i(TAG, "GATT connected to ${device.address}")
    }

    /**
     * 断开 GATT 连接
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
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
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
            // 接收车辆返回的数据 (Notify)
            @Suppress("DEPRECATION")
            val data = characteristic.value
            handleReceivedData(data)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            Log.v(TAG, "onCharacteristicWrite: status=$status")
        }
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
            // 短暂延迟避免 BLE 拥塞
            Thread.sleep(5)
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
     * 发送消息并等待响应
     *
     * @param message 要发送的消息
     * @param timeoutMs 响应超时 (毫秒)
     * @return 响应消息
     */
    suspend fun sendAndWait(message: ByteArray, timeoutMs: Long = TeslaBleConstants.COMMAND_TIMEOUT_MS): ByteArray {
        sendMessage(message)
        return receiveMessage(timeoutMs)
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
