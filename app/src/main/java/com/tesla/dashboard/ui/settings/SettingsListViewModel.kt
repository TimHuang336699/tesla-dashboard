package com.tesla.dashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tesla.dashboard.data.local.SettingsRepository
import com.tesla.dashboard.data.local.VehicleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置列表页 UI 状态数据类
 *
 * @property isPaired BLE 是否已配对
 * @property themeMode 主题模式代码
 * @property unitSystem 单位系统代码 ("metric"/"imperial")
 * @property appLanguage 语言代码 ("system"/"zh"/"en")
 * @property isLoaded 是否已加载真实数据
 */
data class SettingsListUiState(
    val isPaired: Boolean = false,
    val themeMode: String = SettingsRepository.DEFAULT_THEME_MODE,
    val unitSystem: String = SettingsRepository.DEFAULT_UNIT_SYSTEM,
    val appLanguage: String = SettingsRepository.DEFAULT_LANGUAGE,
    val isLoaded: Boolean = false,
)

/**
 * 设置列表页 ViewModel — 一级页面副标题状态
 *
 * 组合配对状态/主题/单位/语言四个 Flow, 供列表页各行的副标题显示当前值。
 *
 * @param settingsRepository 设置持久化仓库(DataStore)
 * @param vehicleRepository 车辆仓库(配对状态权威源, v0.5.1)
 */
@HiltViewModel
class SettingsListViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vehicleRepository: VehicleRepository,
) : ViewModel() {

    /** BLE 配对状态 */
    private val _isPaired = MutableStateFlow(false)

    /**
     * 设置列表页合并后的 UI 状态流
     */
    val uiState: StateFlow<SettingsListUiState> = combine(
        settingsRepository.themeModeFlow,
        settingsRepository.unitSystemFlow,
        settingsRepository.appLanguageFlow,
        _isPaired,
    ) { themeMode, unitSystem, appLanguage, isPaired ->
        SettingsListUiState(
            isPaired = isPaired,
            themeMode = themeMode,
            unitSystem = unitSystem,
            appLanguage = appLanguage,
            isLoaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsListUiState(),
    )

    init {
        viewModelScope.launch {
            _isPaired.value = vehicleRepository.hasPairedVehicles()
        }
    }
}
