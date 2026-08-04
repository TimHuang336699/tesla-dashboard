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
 * 设置页面 UI 状态数据类
 *
 * @property vin Tesla 车辆识别号,空字符串表示未设置
 * @property themeMode 主题模式:"dark"(深色) / "light"(浅色) / "system"(跟随系统)
 * @property batteryModel 车型代码(如 "model_3_long_range"),空字符串表示未设置
 * @property isPaired BLE 是否已配对
 * @property dashBackground 仪表背景代码(如 "stealth"/"ocean"),默认 "default"
 * @property isLoaded 是否已从 DataStore 加载真实数据 (initialValue 为 false)
 */
data class SettingsUiState(
    val vin: String = "",
    val themeMode: String = SettingsRepository.DEFAULT_THEME_MODE,
    val batteryModel: String = "",
    val isPaired: Boolean = false,
    val dashBackground: String = SettingsRepository.DEFAULT_DASH_BACKGROUND,
    val isLoaded: Boolean = false,
)

/**
 * 设置页面 ViewModel — BLE 蓝牙直连版
 *
 * 作为设置页面 UI 层与数据层之间的桥梁,负责:
 * 1. 暴露 [uiState] StateFlow,合并 VIN/主题/车型/配对状态供 UI 观察并填充表单
 * 2. 提供 save 系列方法,将用户修改持久化到 DataStore
 * 3. 当 VIN 更新时,同步更新 [TeslaBleProvider] 的运行时属性
 * 4. 提供 BLE 配对([startPairing])、解绑([unpair])、测试连接([testConnection])方法
 * 5. 暴露 [pairingState] Flow 供 UI 显示配对进度
 *
 * @param settingsRepository 设置持久化仓库(DataStore)
 * @param teslaBleProvider Tesla BLE 数据源
 * @param keyManager BLE 密钥管理器
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val teslaBleProvider: TeslaBleProvider,
    private val keyManager: TeslaKeyManager,
) : ViewModel() {

    /** BLE 配对状态 (用于 UI 显示进度) */
    private val _isPaired = MutableStateFlow(false)

    /**
     * 设置保存专用作用域 — 不随 Activity 销毁取消
     *
     * DataStore 写入通过 [saveVin] / [saveThemeMode] / [saveBatteryModel] 在此作用域执行。
     * 用户修改设置后可能立即退出设置页, 若用 viewModelScope 则 Activity 销毁时会取消
     * 正在进行的写入, 导致"退出后设置未保存、再次进入恢复原值"。
     * 写入任务极短 (<100ms), 独立作用域自然完成后即释放, 无泄漏风险。
     */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 当前正在运行的配对协程句柄 — 持有此 Job 用于 [cancelPairing] 主动取消,
     * 防止 PairingActivity 销毁后协程残留导致后续无法再次配对。
     */
    private var pairingJob: kotlinx.coroutines.Job? = null

    /**
     * 设置页面合并后的 UI 状态流
     *
     * 合并 VIN/主题/车型/配对状态四个 Flow,
     * 任一变化时重新发射完整的 [SettingsUiState]。
     */
    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.vinFlow,
        settingsRepository.themeModeFlow,
        settingsRepository.batteryModelFlow,
        settingsRepository.dashBackgroundFlow,
        _isPaired,
    ) { vin, themeMode, batteryModel, dashBackground, isPaired ->
        SettingsUiState(
            vin = vin,
            themeMode = themeMode,
            batteryModel = batteryModel,
            dashBackground = dashBackground,
            isPaired = isPaired,
            isLoaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState(),
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
     *
     * - 从 [TeslaKeyManager] 加载已配对 VIN,同步到 [TeslaBleProvider]
     * - 收集 vinFlow,持续同步 VIN 变更到 [TeslaBleProvider]
     * - 检查当前配对状态
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
     * 保存主题模式
     *
     * @param themeMode 主题模式:"dark" / "light" / "system"
     */
    fun saveThemeMode(themeMode: String) {
        saveScope.launch {
            settingsRepository.saveThemeMode(themeMode)
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
     * 保存仪表背景
     *
     * @param background 背景代码 ("default"/"stealth"/"ocean"/"nebula"/"crimson"/"wine")
     */
    fun saveDashBackground(background: String) {
        saveScope.launch {
            settingsRepository.saveDashBackground(background)
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
     *
     * 流程:
     * 1. 取消 [pairingJob] (取消 viewModelScope 中正在等待 NFC 响应的协程)
     * 2. 转发到 [TeslaBleProvider.cancelPairing] (释放 GATT 连接 + 重置状态)
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
