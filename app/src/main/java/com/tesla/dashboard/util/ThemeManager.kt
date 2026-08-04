package com.tesla.dashboard.util

import android.content.Context
import android.content.res.Configuration
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
 * 主题颜色集合(纯代码管理,ARGB Int)
 *
 * 所有颜色以 [Int] (ARGB) 形式保存,不再依赖 values/values-night 的 XML 资源。
 * 切换主题时只需更新该数据类实例并推送到 [StateFlow],
 * UI 层收集变化即可实时刷新配色 —— 无需重建 Activity。
 *
 * @property background 全局背景色
 * @property surface 表面色(工具栏、列表底色等)
 * @property cardBackground 卡片背景色
 * @property divider 分割线颜色
 * @property textPrimary 主要文字色
 * @property textSecondary 次要文字色
 * @property accentBlue 蓝色强调色(主操作 / 速度表进度)
 * @property accentGreen 绿色强调色(成功 / 正向状态)
 * @property accentRed 红色强调色(警告 / 错误)
 * @property accentOrange 橙色强调色(提示)
 * @property accentCyan Tesla 青色(NFC 涟漪动画专用,两主题恒定)
 * @property speedometerProgress 速度表进度弧颜色 (橙色指针)
 * @property speedometerBg 速度表背景弧颜色
 * @property speedometerText 速度表数值文字颜色
 * @property speedometerUnit 速度表单位文字颜色
 * @property speedometerTick 速度表刻度线颜色
 * @property speedometerTickText 速度表刻度数字颜色
 */
data class ThemeColors(
    val background: Int,
    val surface: Int,
    val cardBackground: Int,
    val divider: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val accentBlue: Int,
    val accentGreen: Int,
    val accentRed: Int,
    val accentOrange: Int,
    val accentCyan: Int,
    val speedometerProgress: Int,
    val speedometerBg: Int,
    val speedometerText: Int,
    val speedometerUnit: Int,
    val speedometerTick: Int,
    val speedometerTickText: Int,
) {
    companion object {
        /**
         * 深色模式配色(深灰风格)
         *
         * 参考 Apple 暗色模式系统色卡:深灰底色 + 高对比强调色。
         */
        val Dark = ThemeColors(
            background = 0xFF1C1C1E.toInt(),
            surface = 0xFF2C2C2E.toInt(),
            cardBackground = 0xFF2C2C2E.toInt(),
            divider = 0xFF38383A.toInt(),
            textPrimary = 0xFFFFFFFF.toInt(),
            textSecondary = 0xFF8E8E93.toInt(),
            accentBlue = 0xFF0A84FF.toInt(),
            accentGreen = 0xFF30D158.toInt(),
            accentRed = 0xFFFF453A.toInt(),
            accentOrange = 0xFFFF9F0A.toInt(),
            accentCyan = 0xFF00D4FF.toInt(),
            speedometerProgress = 0xFFFF6B00.toInt(),   // 橙色指针 (Dash for Tesla 标志色)
            speedometerBg = 0xFF1A1A2E.toInt(),          // 深蓝黑背景弧
            speedometerText = 0xFFFFFFFF.toInt(),
            speedometerUnit = 0xFF8E8E93.toInt(),
            speedometerTick = 0xFFCCCCCC.toInt(),        // 浅灰刻度线
            speedometerTickText = 0xFFAAAAAA.toInt(),    // 中灰刻度数字
        )

        /**
         * 浅色模式配色(浅灰风格)
         *
         * 参考 Apple 浅色模式系统色卡:浅灰底色 + 鲜明强调色。
         */
        val Light = ThemeColors(
            background = 0xFFF2F2F7.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            cardBackground = 0xFFFFFFFF.toInt(),
            divider = 0xFFE5E5EA.toInt(),
            textPrimary = 0xFF000000.toInt(),
            textSecondary = 0xFF8E8E93.toInt(),
            accentBlue = 0xFF007AFF.toInt(),
            accentGreen = 0xFF34C759.toInt(),
            accentRed = 0xFFFF3B30.toInt(),
            accentOrange = 0xFFFF9500.toInt(),
            accentCyan = 0xFF00D4FF.toInt(),
            speedometerProgress = 0xFFFF6B00.toInt(),   // 橙色指针 (Dash for Tesla 标志色)
            speedometerBg = 0xFFE5E5EA.toInt(),
            speedometerText = 0xFF000000.toInt(),
            speedometerUnit = 0xFF8E8E93.toInt(),
            speedometerTick = 0xFFAAAAAA.toInt(),        // 中灰刻度线
            speedometerTickText = 0xFF888888.toInt(),    // 灰色刻度数字
        )

        /**
         * 特斯拉蓝(深色) — 深蓝黑底色 + 冰蓝强调
         */
        val TeslaBlue = ThemeColors(
            background = 0xFF0B1220.toInt(),
            surface = 0xFF16233A.toInt(),
            cardBackground = 0xFF16233A.toInt(),
            divider = 0xFF24344D.toInt(),
            textPrimary = 0xFFFFFFFF.toInt(),
            textSecondary = 0xFF8E9BB0.toInt(),
            accentBlue = 0xFF3AA0FF.toInt(),
            accentGreen = 0xFF30D158.toInt(),
            accentRed = 0xFFFF453A.toInt(),
            accentOrange = 0xFFFF9F0A.toInt(),
            accentCyan = 0xFF00C6FF.toInt(),
            speedometerProgress = 0xFF00A0FF.toInt(),   // 冰蓝指针
            speedometerBg = 0xFF0E1B33.toInt(),
            speedometerText = 0xFFFFFFFF.toInt(),
            speedometerUnit = 0xFF8E9BB0.toInt(),
            speedometerTick = 0xFF3A5A8C.toInt(),
            speedometerTickText = 0xFF7BA3CC.toInt(),
        )

        /**
         * 森林绿(深色) — 深绿黑底色 + 翠绿强调
         */
        val ForestGreen = ThemeColors(
            background = 0xFF0E1A14.toInt(),
            surface = 0xFF1A2B22.toInt(),
            cardBackground = 0xFF1A2B22.toInt(),
            divider = 0xFF24382E.toInt(),
            textPrimary = 0xFFFFFFFF.toInt(),
            textSecondary = 0xFF8FA897.toInt(),
            accentBlue = 0xFF3AA0FF.toInt(),
            accentGreen = 0xFF2ECC71.toInt(),
            accentRed = 0xFFFF453A.toInt(),
            accentOrange = 0xFFFF9F0A.toInt(),
            accentCyan = 0xFF00D4A0.toInt(),
            speedometerProgress = 0xFF00E676.toInt(),   // 翠绿指针
            speedometerBg = 0xFF122419.toInt(),
            speedometerText = 0xFFFFFFFF.toInt(),
            speedometerUnit = 0xFF8FA897.toInt(),
            speedometerTick = 0xFF2E4A3A.toInt(),
            speedometerTickText = 0xFF6FB08A.toInt(),
        )

        /**
         * 琥珀橙(深色) — 深棕黑底色 + 暖橙强调
         */
        val EmberOrange = ThemeColors(
            background = 0xFF1C140E.toInt(),
            surface = 0xFF2E241A.toInt(),
            cardBackground = 0xFF2E241A.toInt(),
            divider = 0xFF3D3024.toInt(),
            textPrimary = 0xFFFFFFFF.toInt(),
            textSecondary = 0xFFB8A89A.toInt(),
            accentBlue = 0xFF4FC3F7.toInt(),
            accentGreen = 0xFF66BB6A.toInt(),
            accentRed = 0xFFFF5252.toInt(),
            accentOrange = 0xFFFFA726.toInt(),
            accentCyan = 0xFFFFB74D.toInt(),
            speedometerProgress = 0xFFFF6D00.toInt(),   // 烈焰橙指针
            speedometerBg = 0xFF2A1E12.toInt(),
            speedometerText = 0xFFFFFFFF.toInt(),
            speedometerUnit = 0xFFB8A89A.toInt(),
            speedometerTick = 0xFF5A4632.toInt(),
            speedometerTickText = 0xFFC49A6C.toInt(),
        )

        /**
         * 午夜紫(深色) — 深紫黑底色 + 紫罗兰强调
         */
        val MidnightPurple = ThemeColors(
            background = 0xFF14101E.toInt(),
            surface = 0xFF241C33.toInt(),
            cardBackground = 0xFF241C33.toInt(),
            divider = 0xFF332A44.toInt(),
            textPrimary = 0xFFFFFFFF.toInt(),
            textSecondary = 0xFFA99FC2.toInt(),
            accentBlue = 0xFFB388FF.toInt(),
            accentGreen = 0xFF69F0AE.toInt(),
            accentRed = 0xFFFF5252.toInt(),
            accentOrange = 0xFFFFB74D.toInt(),
            accentCyan = 0xFFCE93D8.toInt(),
            speedometerProgress = 0xFFAA00FF.toInt(),   // 紫罗兰指针
            speedometerBg = 0xFF1E1630.toInt(),
            speedometerText = 0xFFFFFFFF.toInt(),
            speedometerUnit = 0xFFA99FC2.toInt(),
            speedometerTick = 0xFF4A3A66.toInt(),
            speedometerTickText = 0xFF9C84C9.toInt(),
        )
    }
}

/**
 * 主题管理器(纯代码主题切换,无需 Activity 重启)
 *
 * 负责日夜主题切换逻辑:监听 [SettingsRepository] 中的主题模式设置,
 * 并通过 [StateFlow] 推送当前主题颜色 [ThemeColors]。
 * UI 层收集 [colors] 变化即可实时刷新配色 ——
 * 不再调用 [androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode],
 * 完全避免 Activity 重建带来的闪烁与状态丢失。
 *
 * ## 主题模式
 * - "dark": 强制深色模式
 * - "light": 强制浅色模式
 * - "system": 跟随系统设置(默认),初始由系统配置决定深浅,
 *   但仍允许通过 [setDarkMode] / [toggleTheme] 手动覆盖
 *
 * ## 使用方式
 * 1. 在 Application onCreate 中注入并调用 [observeTheme] 开始监听 SettingsRepository
 * 2. UI 层通过 [colors] 获取当前配色并订阅变化,实时应用
 * 3. 需要即时切换时调用 [setDarkMode] 或 [toggleTheme]
 * 4. 兼容旧接口 [isDarkMode],供仅需布尔判断的组件使用
 *
 * ## 持久化说明
 * [setDarkMode] / [toggleTheme] 仅更新内存状态并推送新 [ThemeColors],
 * 不会改写 DataStore 中的主题模式 —— 这样在 "system" 模式下可保留
 * "跟随系统" 语义的同时进行临时覆盖。如需持久化新的模式,
 * 请通过 [SettingsRepository.saveThemeMode] 保存,[observeTheme] 会自动响应。
 *
 * @param context 应用上下文(用于读取系统夜间模式配置)
 * @param settingsRepository 设置仓库
 */
@Singleton
class ThemeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    /** 应用级协程作用域 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 当前是否暗色模式(供 UI 层读取,向下兼容旧接口) */
    private val _isDarkMode = MutableStateFlow(true)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    /** 当前主题颜色集合(UI 层收集后实时应用,实现无重启切换) */
    private val _colors = MutableStateFlow(ThemeColors.Dark)
    val colors: StateFlow<ThemeColors> = _colors.asStateFlow()

    /**
     * 开始监听主题设置并自动切换
     *
     * 在 Application.onCreate 中调用。
     * 收集 [SettingsRepository.themeModeFlow],当持久化的主题模式变化时
     * 自动更新 [colors] 与 [isDarkMode]。
     */
    fun observeTheme() {
        scope.launch {
            settingsRepository.themeModeFlow.collect { mode ->
                applyTheme(mode)
            }
        }
    }

    /**
     * 应用主题模式(根据模式字符串解析为具体配色)
     *
     * - "dark": 经典深色
     * - "light": 经典浅色
     * - "tesla_blue": 特斯拉蓝(深色)
     * - "forest_green": 森林绿(深色)
     * - "ember_orange": 琥珀橙(深色)
     * - "midnight_purple": 午夜紫(深色)
     * - 其他(含 "system"): 跟随系统深/浅, 默认使用经典配色
     *
     * @param mode 主题模式字符串
     */
    private fun applyTheme(mode: String) {
        val colors = when (mode) {
            "dark" -> ThemeColors.Dark
            "light" -> ThemeColors.Light
            "tesla_blue" -> ThemeColors.TeslaBlue
            "forest_green" -> ThemeColors.ForestGreen
            "ember_orange" -> ThemeColors.EmberOrange
            "midnight_purple" -> ThemeColors.MidnightPurple
            else -> if (isSystemDarkMode()) ThemeColors.Dark else ThemeColors.Light
        }
        setThemeColors(colors)
    }

    /**
     * 应用指定配色并同步深/浅标记
     *
     * 彩色主题均为深色系, isDarkMode 标记设为 true(仅浅色为 false)。
     *
     * @param colors 目标主题配色
     */
    private fun setThemeColors(colors: ThemeColors) {
        _colors.value = colors
        _isDarkMode.value = colors !== ThemeColors.Light
    }

    /**
     * 读取系统当前是否处于夜间(深色)模式
     *
     * @return true 表示系统当前为深色模式
     */
    private fun isSystemDarkMode(): Boolean {
        val currentNightMode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 立即切换深色/浅色模式(无需 Activity 重启)
     *
     * 同步更新 [isDarkMode] 与 [colors] 两个 StateFlow,
     * 所有订阅者会立即收到新配色。
     *
     * 注意:此方法仅更新内存状态,不会持久化到 DataStore,
     * 因此在 "system" 模式下可安全用作临时覆盖。
     * 如需持久化,请通过 [SettingsRepository.saveThemeMode] 保存。
     *
     * @param isDark true=深色模式,false=浅色模式
     */
    fun setDarkMode(isDark: Boolean) {
        _isDarkMode.value = isDark
        _colors.value = if (isDark) ThemeColors.Dark else ThemeColors.Light
    }

    /**
     * 在深色与浅色之间切换
     *
     * 等价于 [setDarkMode]`(!isDarkMode.value)`。
     */
    fun toggleTheme() {
        setDarkMode(!_isDarkMode.value)
    }
}
