package com.tesla.dashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tesla.dashboard.data.local.SettingsRepository
import com.tesla.dashboard.data.source.ble.TeslaBleProvider
import com.tesla.dashboard.data.source.ble.TeslaKeyManager
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
 * BLE 设置页面 UI 状态数据类
 *
 * @property vin Tesla 车辆识别号,空字符串表示未设置
 * @property batteryModel 车型代码(如 "model_3_long_range"),空字符串表示未设置
 * @property isPaired BLE 是否已配对
 * @property isLoaded 是否已从 DataStore 加载真实数据 (initialValue 为 false)
 */
data class BleSettingsUiState(
    val vin: String = "",
    val batteryModel: String = "",
    val isPaired: Boolean = false,
    val isLoaded: Boolean = false,
)

/**
 * BLE 设置页面 ViewModel — 承载蓝牙与车辆设置
 *
 * 从原 [SettingsViewModel] 拆分而来, 负责:
 * 1. 暴露 [uiState] StateFlow, 合并 VIN/车型/配对状态供 UI 观察并填充表单
 * 2. 提供 save 系列方法 (VIN/车型) 持久化到 DataStore
 * 3. 当 VIN 更新时同步更新 [TeslaBleProvider] 的运行时属性
 * 4. 提供 BLE 配对([startPairing])、解绑([unpair])、测试连接([testConnection])方法
 * 5. 暴露 [pairingState] Flow 供 UI 显示配对进度
 *
 * 同时被 [com.tesla.dashboard.ui.pairing.PairingActivity] 复用 (配对向导)。
 *
 * @param settingsRepository 设置持久化仓库(DataStore)
 * @param teslaBleProvider Tesla BLE 数据源
 * @param keyManager BLE 密钥管理器
 */
@HiltViewModel
class BleSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val teslaBleProvider: TeslaBleProvider,
    private val keyManager: TeslaKeyManager,
) : ViewModel() {

    /** BLE 配对状态 (用于 UI 显示进度) */
    private val _isPaired = MutableStateFlow(false)

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
     * 合并 VIN/车型/配对状态三个 Flow, 任一变化时重新发射完整的 [BleSettingsUiState]。
     */
    val uiState: StateFlow<BleSettingsUiState> = combine(
        settingsRepository.vinFlow,
        settingsRepository.batteryModelFlow,
        _isPaired,
    ) { vin, batteryModel, isPaired ->
        BleSettingsUiState(
            vin = vin,
            batteryModel = batteryModel,
            isPaired = isPaired,
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
     * 初始化 — 加载已保存的配对状态并同步 VIN
     */
    init {
        viewModelScope.launch {
            // 检查配对状态
            _isPaired.value = keyManager.isPaired()

            // 加载已配对 VIN 并同步到 BLE Provider
            val pairedVin = keyManager.loadPairedVin()
            if (!pairedVin.isNullOrBlank()) {
                teslaBleProvider.vin = pairedVin
            }
        }
        viewModelScope.launch {
            // 持续同步 VIN 设置到 BLE Provider
            settingsRepository.vinFlow.collect { vin ->
                teslaBleProvider.vin = vin.ifBlank { null }
            }
        }
    }

    /**
     * 保存 VIN 并同步到 BLE Provider
     *
     * @param vin 用户输入的车辆识别号
     */
    fun saveVin(vin: String) {
        saveScope.launch {
            settingsRepository.saveVin(vin)
            teslaBleProvider.vin = vin.trim().ifBlank { null }
        }
    }

    /**
     * 保存车型代码
     *
     * @param batteryModel 车型代码(对应 BatteryConfig 中的 key)
     */
    fun saveBatteryModel(batteryModel: String) {
        saveScope.launch {
            settingsRepository.saveBatteryModel(batteryModel)
        }
    }

    /**
     * 开始 BLE 配对
     *
     * 调用 [TeslaBleProvider.startPairing] 发起配对流程:
     * 生成密钥 → 扫描车辆 → ECDH 握手 → 发送 add-key-request → 等待 NFC 确认
     *
     * 配对成功后:
     * - 更新 [isPaired] 状态
     * - 将 VIN 保存到 SettingsRepository
     * - 同步 VIN 到 BLE Provider
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
            if (success) {
                _isPaired.value = true
                settingsRepository.saveVin(vin)
                teslaBleProvider.vin = vin.trim()
            }
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
     * 解除 BLE 配对
     *
     * 清除本地密钥和配对信息,重置配对状态。
     */
    fun unpair() {
        viewModelScope.launch {
            teslaBleProvider.unpair()
            _isPaired.value = false
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
