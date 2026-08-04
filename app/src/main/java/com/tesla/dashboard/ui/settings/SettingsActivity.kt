package com.tesla.dashboard.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tesla.dashboard.R
import com.tesla.dashboard.databinding.ActivitySettingsBinding
import com.tesla.dashboard.databinding.ItemSettingsRowBinding
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.ThemeColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 设置列表页 (一级页面) — 安卓分级设置结构
 *
 * 列表展示 5 组设置入口, 点击进入对应二级详情页:
 * - 蓝牙与车辆 → [BleSettingsActivity]
 * - 显示与外观 → [AppearanceSettingsActivity]
 * - 单位 → [UnitSettingsActivity]
 * - 语言 → [LanguageSettingsActivity]
 * - 关于 → [AboutActivity]
 *
 * 每行显示"标题 + 当前值副标题 + 箭头", 副标题由 [SettingsListViewModel]
 * 实时驱动 (配对状态/主题/单位/语言)。
 *
 * @see SettingsListViewModel
 */
@AndroidEntryPoint
class SettingsActivity : BaseImmersiveActivity() {

    /** ViewBinding 实例 */
    private lateinit var binding: ActivitySettingsBinding

    /** 设置列表 ViewModel, 由 Hilt 自动提供 */
    private val viewModel: SettingsListViewModel by viewModels()

    /** 行数据: 标题/副标题(当前值)/目标 Activity */
    private data class SettingsRow(
        val titleRes: Int,
        val summaryRes: Int,
        val target: Class<*>,
    )

    /** 5 组设置行 (标题 + 副标题标签 + 目标页) */
    private val rows = listOf(
        SettingsRow(R.string.settings_ble_vehicle, R.string.settings_ble_vehicle, BleSettingsActivity::class.java),
        SettingsRow(R.string.settings_display_appearance, R.string.settings_display_appearance, AppearanceSettingsActivity::class.java),
        SettingsRow(R.string.settings_unit, R.string.settings_unit, UnitSettingsActivity::class.java),
        SettingsRow(R.string.settings_language, R.string.settings_language, LanguageSettingsActivity::class.java),
        SettingsRow(R.string.settings_about, R.string.settings_about, AboutActivity::class.java),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // 填充设置行
        populateRows()

        // 观察主题流 — 实时应用配色
        observeThemeColors()
        applyThemeColors(themeManager.colors.value)

        // 观察列表状态 — 更新副标题
        observeListState()
    }

    /**
     * 动态填充设置行 (每行: 标题 + 副标题 + 箭头 + 点击跳转)
     */
    private fun populateRows() {
        val container = binding.rowsContainer
        container.removeAllViews()

        rows.forEach { row ->
            val rowBinding = ItemSettingsRowBinding.inflate(
                LayoutInflater.from(this),
                container,
                false,
            )
            rowBinding.tvRowTitle.setText(row.titleRes)
            rowBinding.tvRowSummary.setText(row.summaryRes)
            rowBinding.root.setOnClickListener {
                startActivity(Intent(this, row.target))
            }
            container.addView(rowBinding.root)
        }
    }

    /**
     * 观察列表状态, 更新各行的副标题 (当前值)
     */
    private fun observeListState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (!state.isLoaded) return@collect
                    updateRowSummaries(state)
                }
            }
        }
    }

    /**
     * 更新各行副标题为当前值
     *
     * @param state 当前设置列表状态
     */
    private fun updateRowSummaries(state: SettingsListUiState) {
        val container = binding.rowsContainer
        val rowCount = container.childCount
        if (rowCount < rows.size) return

        // 行 0: 蓝牙与车辆 — 已配对/未配对
        (container.getChildAt(0).findViewById<android.widget.TextView>(R.id.tvRowSummary))
            .setText(if (state.isPaired) R.string.settings_paired else R.string.settings_not_paired)

        // 行 1: 显示与外观 — 当前主题名
        (container.getChildAt(1).findViewById<android.widget.TextView>(R.id.tvRowSummary))
            .text = themeDisplayName(state.themeMode)

        // 行 2: 单位 — 公制/英制
        (container.getChildAt(2).findViewById<android.widget.TextView>(R.id.tvRowSummary))
            .setText(
                if (state.unitSystem == "imperial") R.string.settings_unit_imperial
                else R.string.settings_unit_metric
            )

        // 行 3: 语言 — 跟随系统/中文/English
        (container.getChildAt(3).findViewById<android.widget.TextView>(R.id.tvRowSummary))
            .text = languageDisplayName(state.appLanguage)

        // 行 4: 关于 — 版本号 (保持原标题, 由 About 页展示)
    }

    /**
     * 主题代码 → 显示名
     */
    private fun themeDisplayName(mode: String): String {
        val nameRes = when (mode) {
            "dark" -> R.string.settings_theme_dark
            "light" -> R.string.settings_theme_light
            "tesla_blue" -> R.string.theme_option_tesla_blue_dark
            "tesla_blue_light" -> R.string.theme_option_tesla_blue_light
            "forest_green" -> R.string.theme_option_forest_green_dark
            "forest_green_light" -> R.string.theme_option_forest_green_light
            "ember_orange" -> R.string.theme_option_ember_orange_dark
            "ember_orange_light" -> R.string.theme_option_ember_orange_light
            "midnight_purple" -> R.string.theme_option_midnight_purple_dark
            "midnight_purple_light" -> R.string.theme_option_midnight_purple_light
            else -> R.string.settings_theme_system
        }
        return getString(nameRes)
    }

    /**
     * 语言代码 → 显示名
     */
    private fun languageDisplayName(language: String): String {
        val nameRes = when (language) {
            "zh" -> R.string.settings_language_zh
            "en" -> R.string.settings_language_en
            else -> R.string.settings_language_system
        }
        return getString(nameRes)
    }

    /**
     * 应用主题颜色到设置列表页
     *
     * @param c 当前主题颜色集合
     */
    override fun applyThemeColors(c: ThemeColors) {
        currentColors = c

        binding.rootLayout.setBackgroundColor(c.background)
        binding.btnBack.imageTintList = ColorStateList.valueOf(c.accentCyan)
        binding.tvTitle.setTextColor(c.textPrimary)

        // 更新所有行的配色
        val container = binding.rowsContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.findViewById<android.widget.TextView>(R.id.tvRowTitle)
                ?.setTextColor(c.textPrimary)
            child.findViewById<android.widget.TextView>(R.id.tvRowSummary)
                ?.setTextColor(c.textSecondary)
            child.findViewById<android.widget.TextView>(R.id.tvRowChevron)
                ?.setTextColor(c.textSecondary)
        }
    }
}
