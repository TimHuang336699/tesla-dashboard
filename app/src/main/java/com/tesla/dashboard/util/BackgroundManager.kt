package com.tesla.dashboard.util

import android.content.Context
import com.tesla.dashboard.R
import com.tesla.dashboard.data.local.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 仪表背景管理器 — Dash for Tesla 风格多背景切换
 *
 * 提供 6 种仪表背景 (复刻 Dash for Tesla 1.8.0 的 classic_cluster 与 default_bg 资源):
 * - "default": 默认氛围背景 (bg_dashboard_atmosphere 渐变)
 * - "stealth": 黑武士 (stealth_bg.png, 深灰黑 + 红色点缀)
 * - "ocean": 深海蓝 (ic_bg_2.png, 深蓝黑)
 * - "nebula": 深紫星云 (ic_bg_3.png, 深紫)
 * - "crimson": 深红 (ic_bg_4.png, 深红黑)
 * - "wine": 酒红 (ic_bg_5.png, 酒红黑)
 *
 * ## 使用方式
 * 1. 在 Application onCreate 中调用 [observeBackground] 开始监听设置
 * 2. UI 层通过 [backgroundRes] 获取当前背景资源 ID 并订阅变化
 *
 * @param context 应用上下文
 * @param settingsRepository 设置仓库
 */
@Singleton
class BackgroundManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    /** 应用级协程作用域 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 当前背景资源 ID (UI 层收集后实时应用) */
    private val _backgroundRes = MutableStateFlow(R.drawable.bg_dashboard_atmosphere)
    val backgroundRes: StateFlow<Int> = _backgroundRes.asStateFlow()

    /** 背景代码 → drawable 资源映射 */
    private val backgroundMap: Map<String, Int> = mapOf(
        "default" to R.drawable.bg_dashboard_atmosphere,
        "stealth" to R.drawable.stealth_bg,
        "ocean" to R.drawable.ic_bg_2,
        "nebula" to R.drawable.ic_bg_3,
        "crimson" to R.drawable.ic_bg_4,
        "wine" to R.drawable.ic_bg_5,
    )

    /**
     * 开始监听背景设置并自动切换
     *
     * 在 Application.onCreate 中调用。
     */
    fun observeBackground() {
        scope.launch {
            settingsRepository.dashBackgroundFlow.collect { code ->
                _backgroundRes.value = backgroundMap[code] ?: R.drawable.bg_dashboard_atmosphere
            }
        }
    }

    /**
     * 立即应用指定背景 (供设置页即时预览)
     *
     * @param code 背景代码
     */
    fun applyBackground(code: String) {
        _backgroundRes.value = backgroundMap[code] ?: R.drawable.bg_dashboard_atmosphere
    }
}
