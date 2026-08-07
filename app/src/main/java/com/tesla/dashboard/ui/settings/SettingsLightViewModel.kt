package com.tesla.dashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tesla.dashboard.data.local.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 轻量设置 ViewModel — 外观/语言/单位二级页共用
 *
 * 组合 [SettingsRepository] 的主题/语言/单位三个 Flow 供下拉/单选页填充,
 * 并提供对应的 save 方法即时持久化。
 *
 * @param settingsRepository 设置持久化仓库(DataStore)
 */
@HiltViewModel
class SettingsLightViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** 设置保存专用作用域 — 不随 Activity 销毁取消 */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 主题模式流 */
    val themeModeFlow: Flow<String> = settingsRepository.themeModeFlow

    /** 应用语言流 */
    val appLanguageFlow: Flow<String> = settingsRepository.appLanguageFlow

    /** 单位系统流 */
    val unitSystemFlow: Flow<String> = settingsRepository.unitSystemFlow

    /** 转向灯显示开关流 (v0.5.0) */
    val showTurnSignalsFlow: Flow<Boolean> = settingsRepository.showTurnSignalsFlow

    /** 保存主题模式 */
    fun saveThemeMode(themeMode: String) {
        saveScope.launch {
            settingsRepository.saveThemeMode(themeMode)
        }
    }

    /** 保存应用语言 */
    fun saveAppLanguage(language: String) {
        saveScope.launch {
            settingsRepository.saveAppLanguage(language)
        }
    }

    /** 保存单位系统 */
    fun saveUnitSystem(unitSystem: String) {
        saveScope.launch {
            settingsRepository.saveUnitSystem(unitSystem)
        }
    }

    /** 保存转向灯显示开关 (v0.5.0) */
    fun saveShowTurnSignals(show: Boolean) {
        saveScope.launch {
            settingsRepository.saveShowTurnSignals(show)
        }
    }
}
