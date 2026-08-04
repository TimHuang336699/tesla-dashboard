package com.tesla.dashboard.ui.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.tesla.dashboard.R
import com.tesla.dashboard.databinding.ActivityAppearanceSettingsBinding
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.ThemeColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 主题选项数据类
 *
 * @property nameRes 用户可见的主题名称资源 ID (随语言切换)
 * @property code 主题代码,对应 [ThemeManager] 的 applyTheme 分支
 */
private data class ThemeOption(val nameRes: Int, val code: String)

/**
 * 显示与外观设置二级页 — 主题选择
 *
 * 继承 [BaseImmersiveActivity] 获得全屏沉浸式与主题实时配色。
 */
@AndroidEntryPoint
class AppearanceSettingsActivity : BaseImmersiveActivity() {

    /** ViewBinding 实例 */
    private lateinit var binding: ActivityAppearanceSettingsBinding

    /** 表单是否已填充标记 */
    private var isFormPopulated = false

    /**
     * 可选主题列表(名称资源 ID ↔ 主题代码)
     */
    private val themeOptions = listOf(
        ThemeOption(R.string.settings_theme_system, "system"),
        ThemeOption(R.string.settings_theme_dark, "dark"),
        ThemeOption(R.string.settings_theme_light, "light"),
        ThemeOption(R.string.theme_option_tesla_blue_dark, "tesla_blue"),
        ThemeOption(R.string.theme_option_tesla_blue_light, "tesla_blue_light"),
        ThemeOption(R.string.theme_option_forest_green_dark, "forest_green"),
        ThemeOption(R.string.theme_option_forest_green_light, "forest_green_light"),
        ThemeOption(R.string.theme_option_ember_orange_dark, "ember_orange"),
        ThemeOption(R.string.theme_option_ember_orange_light, "ember_orange_light"),
        ThemeOption(R.string.theme_option_midnight_purple_dark, "midnight_purple"),
        ThemeOption(R.string.theme_option_midnight_purple_light, "midnight_purple_light"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAppearanceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        setupThemeDropdown()

        // 观察主题流 — 实时应用配色
        observeThemeColors()

        // 立即应用当前主题 (避免初始回退色闪现)
        applyThemeColors(themeManager.colors.value)

        // 观察已保存的主题设置 (填充下拉框)
        observeSavedTheme()
    }

    /**
     * 设置主题下拉菜单
     */
    private fun setupThemeDropdown() {
        val adapter = ArrayAdapter(
            this,
            R.layout.item_dropdown,
            themeOptions.map { getString(it.nameRes) },
        )
        binding.actvTheme.setAdapter(adapter)

        binding.actvTheme.setOnItemClickListener { _, _, position, _ ->
            val mode = themeOptions[position].code
            // 主题选择后即时保存并实时应用
            if (isFormPopulated) {
                viewModel.saveThemeMode(mode)
                themeManager.setThemeMode(mode)
            }
        }
    }

    /**
     * 观察已保存的主题设置以填充下拉框
     */
    private fun observeSavedTheme() {
        lifecycleScope.launch {
            viewModel.themeModeFlow.collect { mode ->
                if (!isFormPopulated) {
                    val option = themeOptions.find { it.code == mode }
                    if (option != null) {
                        binding.actvTheme.setText(getString(option.nameRes), false)
                    }
                    isFormPopulated = true
                }
            }
        }
    }

    /**
     * 轻量设置 ViewModel (仅主题流)
     */
    private val viewModel: SettingsLightViewModel by viewModels()

    /**
     * 应用主题颜色
     *
     * @param c 当前主题颜色集合
     */
    override fun applyThemeColors(c: ThemeColors) {
        currentColors = c

        binding.rootScroll.setBackgroundColor(c.background)
        binding.btnBack.imageTintList = ColorStateList.valueOf(c.accentCyan)
        binding.tvTitle.setTextColor(c.textPrimary)

        binding.cardAppearance.strokeColor = c.divider
        binding.cardAppearance.setCardBackgroundColor(c.cardBackground)
        binding.tvSectionAppearance.setTextColor(c.accentCyan)
        binding.tvThemeLabel.setTextColor(c.textSecondary)
        binding.tilTheme.boxStrokeColor = c.accentCyan
        binding.actvTheme.setTextColor(c.textPrimary)
    }
}
