package com.tesla.dashboard.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tesla.dashboard.R
import com.tesla.dashboard.databinding.ActivitySettingsBinding
import com.tesla.dashboard.databinding.ItemSettingsRowBinding
import com.tesla.dashboard.databinding.ItemSettingsSectionHeaderBinding
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.LogExporter
import com.tesla.dashboard.util.ThemeColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 设置列表页 (一级页面) — 手机设置风格 (大分组 → 二级页面)
 *
 * 每个大分组点击后跳转到对应的二级设置页面:
 * - 车辆 → BleSettingsActivity
 * - 显示 → AppearanceSettingsActivity
 * - 通用 → UnitSettingsActivity / LanguageSettingsActivity
 * - 数据 → DataSettingsActivity
 * - 通知 → NotificationSettingsActivity
 * - 安全 → SecuritySettingsActivity
 * - 高级 → AdvancedSettingsActivity
 * - 关于 → AboutActivity
 */
@AndroidEntryPoint
class SettingsActivity : BaseImmersiveActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsListViewModel by viewModels()

    /** 行数据 */
    private data class SettingsRow(
        val titleRes: Int,
        val summaryRes: Int = 0,
        val hasSummary: Boolean = true,
        val target: Class<*>? = null,
        val action: ((SettingsActivity) -> Unit)? = null,
    )

    /** 分组数据 */
    private data class SettingsGroup(
        val headerRes: Int,
        val rows: List<SettingsRow>,
    )

    private val rowViews = mutableListOf<android.view.View>()

    /** 分组配置 — 每个大项只有一行, 点击进入二级页面 */
    private val groups = listOf(
        SettingsGroup(
            headerRes = R.string.settings_group_vehicle,
            rows = listOf(
                SettingsRow(
                    titleRes = R.string.settings_ble_vehicle,
                    summaryRes = R.string.settings_not_paired,
                    target = BleSettingsActivity::class.java,
                ),
            ),
        ),
        SettingsGroup(
            headerRes = R.string.settings_group_display,
            rows = listOf(
                SettingsRow(
                    titleRes = R.string.settings_display_appearance,
                    summaryRes = R.string.settings_theme_system,
                    target = AppearanceSettingsActivity::class.java,
                ),
            ),
        ),
        SettingsGroup(
            headerRes = R.string.settings_group_general,
            rows = listOf(
                SettingsRow(
                    titleRes = R.string.settings_unit,
                    summaryRes = R.string.settings_unit_metric,
                    target = UnitSettingsActivity::class.java,
                ),
                SettingsRow(
                    titleRes = R.string.settings_language,
                    summaryRes = R.string.settings_language_system,
                    target = LanguageSettingsActivity::class.java,
                ),
                SettingsRow(
                    titleRes = R.string.settings_export_logs,
                    hasSummary = false,
                    action = { activity -> LogExporter.export(activity) },
                ),
            ),
        ),
        SettingsGroup(
            headerRes = R.string.settings_group_data,
            rows = listOf(
                SettingsRow(
                    titleRes = R.string.settings_group_data,
                    summaryRes = R.string.settings_gnss_fallback_summary,
                    target = DataSettingsActivity::class.java,
                ),
            ),
        ),
        SettingsGroup(
            headerRes = R.string.settings_group_notification,
            rows = listOf(
                SettingsRow(
                    titleRes = R.string.settings_group_notification,
                    summaryRes = R.string.settings_notify_low_battery_summary,
                    target = NotificationSettingsActivity::class.java,
                ),
            ),
        ),
        SettingsGroup(
            headerRes = R.string.settings_group_security,
            rows = listOf(
                SettingsRow(
                    titleRes = R.string.settings_group_security,
                    summaryRes = R.string.settings_security_lock_confirm_summary,
                    target = SecuritySettingsActivity::class.java,
                ),
            ),
        ),
        SettingsGroup(
            headerRes = R.string.settings_group_advanced,
            rows = listOf(
                SettingsRow(
                    titleRes = R.string.settings_group_advanced,
                    summaryRes = R.string.settings_advanced_debug_summary,
                    target = AdvancedSettingsActivity::class.java,
                ),
            ),
        ),
        SettingsGroup(
            headerRes = R.string.settings_group_about,
            rows = listOf(
                SettingsRow(
                    titleRes = R.string.settings_about,
                    summaryRes = R.string.settings_about_summary,
                    target = AboutActivity::class.java,
                ),
            ),
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        populateGroups()
        observeThemeColors()
        applyThemeColors(themeManager.colors.value)
        observeListState()
    }

    private fun populateGroups() {
        val container = binding.rowsContainer
        container.removeAllViews()
        rowViews.clear()
        val inflater = LayoutInflater.from(this)

        groups.forEach { group ->
            val headerBinding = ItemSettingsSectionHeaderBinding.inflate(inflater, container, false)
            headerBinding.tvSectionTitle.setText(group.headerRes)
            container.addView(headerBinding.root)

            group.rows.forEach { row ->
                val rowBinding = ItemSettingsRowBinding.inflate(inflater, container, false)
                rowBinding.tvRowTitle.setText(row.titleRes)
                if (row.hasSummary && row.summaryRes != 0) {
                    rowBinding.tvRowSummary.setText(row.summaryRes)
                } else {
                    rowBinding.tvRowSummary.visibility = android.view.View.GONE
                }
                rowBinding.root.setOnClickListener {
                    row.action?.invoke(this) ?: row.target?.let { target ->
                        startActivity(Intent(this, target))
                    }
                }
                container.addView(rowBinding.root)
                rowViews.add(rowBinding.root)
            }
        }
    }

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

    private fun updateRowSummaries(state: SettingsListUiState) {
        if (rowViews.isEmpty()) return

        // 0: 蓝牙与车辆
        rowViews.getOrNull(0)?.findViewById<TextView>(R.id.tvRowSummary)
            ?.setText(if (state.isPaired) R.string.settings_paired else R.string.settings_not_paired)

        // 1: 显示与外观
        rowViews.getOrNull(1)?.findViewById<TextView>(R.id.tvRowSummary)
            ?.text = themeDisplayName(state.themeMode)

        // 2: 单位
        rowViews.getOrNull(2)?.findViewById<TextView>(R.id.tvRowSummary)
            ?.setText(
                if (state.unitSystem == "imperial") R.string.settings_unit_imperial
                else R.string.settings_unit_metric
            )

        // 3: 语言
        rowViews.getOrNull(3)?.findViewById<TextView>(R.id.tvRowSummary)
            ?.text = languageDisplayName(state.appLanguage)
    }

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

    private fun languageDisplayName(language: String): String {
        val nameRes = when (language) {
            "zh" -> R.string.settings_language_zh
            "en" -> R.string.settings_language_en
            else -> R.string.settings_language_system
        }
        return getString(nameRes)
    }

    override fun applyThemeColors(c: ThemeColors) {
        currentColors = c
        binding.rootLayout.setBackgroundColor(c.background)
        binding.btnBack.imageTintList = ColorStateList.valueOf(c.accentCyan)
        binding.tvTitle.setTextColor(c.textPrimary)
        val container = binding.rowsContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.findViewById<TextView>(R.id.tvSectionTitle)?.setTextColor(c.textSecondary)
            child.findViewById<TextView>(R.id.tvRowTitle)?.setTextColor(c.textPrimary)
            child.findViewById<TextView>(R.id.tvRowSummary)?.setTextColor(c.textSecondary)
            child.findViewById<TextView>(R.id.tvRowChevron)?.setTextColor(c.textSecondary)
        }
    }
}
