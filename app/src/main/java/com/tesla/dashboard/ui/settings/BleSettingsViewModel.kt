package com.tesla.dashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tesla.dashboard.data.local.VehicleRepository
import com.tesla.dashboard.data.model.VehicleInfo
import com.tesla.dashboard.data.source.ble.TeslaBleProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * BLE 设置页面 UI 状态数据类 (v0.5.1 多车支持)
 *
 * @property vehicles 已配对车辆列表
 * @property currentVin 当前选中的车辆 VIN, 空字符串表示未选择
 * @property isLoaded 是否已从 DataStore 加载真实数据 (initialValue 为 false)
 */
data class BleSettingsUiState(
    val vehicles: List<VehicleInfo> = emptyList(),
    val currentVin: String = "",
    val isLoaded: Boolean = false,
) {
    /** 当前选中的车辆, 未选择时返回 null */
    val currentVehicle: VehicleInfo? get() = vehicles.find { it.vin == currentVin }

    /** 当前是否有已选中的已配对车辆 */
    val isPaired: Boolean get() = currentVehicle != null
}

/**
 * BLE 设置页面 ViewModel — 多车管理 (v0.5.1)
 *
 * 从原 [SettingsViewModel] 拆分而来, 负责:
 * 1. 暴露 [uiState] StateFlow, 合并车辆列表与当前 VIN 供 UI 观察
 * 2. 提供 BLE 配对([startPairing])、按 VIN 解绑([unpair])、切换当前车辆([switchVehicle])、
 *    按车保存车型([saveBatteryModel])、测试连接([testConnection])方法
 * 3. 暴露 [pairingState] Flow 供 UI 显示配对进度
 *
 * 同时被 [com.tesla.dashboard.ui.pairing.PairingActivity] 复用 (配对向导)。
 *
 * @param vehicleRepository 车辆仓库(多车列表/当前车辆, DataStore)
 * @param teslaBleProvider Tesla BLE 数据源
 */
@HiltViewModel
class BleSettingsViewModel @Inject constructor(
    private val vehicleRepository: VehicleRepository,
    private val teslaBleProvider: TeslaBleProvider,
) : ViewModel() {

    /**
     * 设置保存专用作用域 — 不随 Activity 销毁取消
     *
     * DataStore 写入极短 (<100ms), 独立作用域避免"退出后设置未保存"。
     */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 当前正在运行的配对协程句柄 — 持有此 Job 用于 [cancelPairing] 主动取消
     */
    private var pairingJob: Job? = null

    /**
     * 设置页面合并后的 UI 状态流
     *
     * 合并车辆列表与当前 VIN, 任一变化时重新发射完整的 [BleSettingsUiState]。
     */
    val uiState: StateFlow<BleSettingsUiState> = combine(
        vehicleRepository.vehiclesFlow,
        vehicleRepository.currentVinFlow,
    ) { vehicles, currentVin ->
        BleSettingsUiState(
            vehicles = vehicles,
            currentVin = currentVin,
            isLoaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = BleSettingsUiState(),
    )

    /**
     * BLE 配对进度状态流
     *
     * 从 [TeslaBleProvider.pairingState] 获取,UI 可观察以显示配对进度文字。
     */
    val pairingState: StateFlow<TeslaBleProvider.PairingState> =
        teslaBleProvider.pairingState.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = TeslaBleProvider.PairingState.Idle,
        )

    /**
     * 初始化 — 同步当前车辆 VIN 到 BLE Provider
     */
    init {
        viewModelScope.launch {
            val currentVin = vehicleRepository.getCurrentVin()
            teslaBleProvider.vin = currentVin.ifBlank { null }
        }
    }

    /**
     * 开始 BLE 配对
     *
     * 调用 [TeslaBleProvider.startPairing] 发起配对流程:
     * 生成密钥 → 扫描车辆 → ECDH 握手 → 发送 add-key-request → 等待 NFC 确认
     *
     * 配对成功后车辆自动加入列表并设为当前车辆 (由 Provider 内部完成)。
     *
     * 注意: 每次调用都会先取消上一个 [pairingJob] 残留协程,
     * 保证中途退出后再次进入 PairingActivity 不会卡死。
     *
     * @param vin 待配对的车辆识别号
     * @param onResult 结果回调,true = 配对成功
     */
    fun startPairing(vin: String, onResult: (Boolean) -> Unit) {
        // 先取消旧协程, 防止中途退出导致协程残留阻塞下次配对
        pairingJob?.takeIf { it.isActive }?.cancel()
        pairingJob = viewModelScope.launch {
            val success = teslaBleProvider.startPairing(vin.trim())
            pairingJob = null
            onResult(success)
        }
    }

    /**
     * 主动取消当前配对流程
     *
     * 由 PairingActivity.onDestroy() 调用, 解决"配对中途退出会卡死、无法再次进入配对页"问题。
     */
    fun cancelPairing() {
        pairingJob?.takeIf { it.isActive }?.cancel()
        pairingJob = null
        teslaBleProvider.cancelPairing()
    }

    /**
     * 解绑指定车辆 (v0.5.1)
     *
     * 从车辆列表中移除该车辆, 若为当前车辆则自动切换到剩余车辆。
     *
     * @param vin 要解绑的车辆 VIN
     */
    fun unpair(vin: String) {
        viewModelScope.launch {
            teslaBleProvider.unpair(vin)
        }
    }

    /**
     * 切换当前车辆 (v0.5.1)
     *
     * @param vin 目标车辆 VIN (必须是已配对车辆)
     */
    fun switchVehicle(vin: String) {
        viewModelScope.launch {
            teslaBleProvider.switchVehicle(vin)
        }
    }

    /**
     * 取消当前车辆选择 (v0.5.1)
     *
     * 清空当前 VIN, 进入"添加新车"模式 (输入框解锁)。
     */
    fun clearCurrentSelection() {
        viewModelScope.launch {
            teslaBleProvider.clearCurrentSelection()
        }
    }

    /**
     * 保存指定车辆的车型代码 (v0.5.1)
     *
     * @param vin 车辆 VIN
     * @param batteryModel 车型代码(对应 BatteryConfig 中的 key)
     */
    fun saveBatteryModel(vin: String, batteryModel: String) {
        saveScope.launch {
            vehicleRepository.updateBatteryModel(vin, batteryModel)
        }
    }

    /**
     * 测试 BLE 连接
     *
     * 尝试扫描并连接车辆,验证已配对的密钥是否有效。
     *
     * @param vin 车辆识别号
     * @param onResult 结果回调,true = 连接成功
     */
    fun testConnection(vin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = teslaBleProvider.testConnection(vin.trim())
            onResult(success)
        }
    }
}
