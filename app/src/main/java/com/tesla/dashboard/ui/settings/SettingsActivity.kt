package com.tesla.dashboard.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tesla.dashboard.R
import com.tesla.dashboard.databinding.ActivitySettingsBinding
import com.tesla.dashboard.databinding.ItemSettingsRowBinding
import com.tesla.dashboard.databinding.ItemSettingsSectionHeaderBinding
import com.tesla.dashboard.databinding.ItemSettingsSwitchBinding
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.LogExporter
import com.tesla.dashboard.util.ThemeColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 设置列表页 (一级页面) — 手机设置风格 (大分组 → 二级小项)
 *
 * 支持两种行类型:
 * - **普通行**: 标题 + 副标题 + 箭头 → 跳转二级页面或执行动作
 * - **开关行**: 标题 + 副标题 + Switch → 直接切换开关
 */
@AndroidEntryPoint
class SettingsActivity : BaseImmersiveActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsListViewModel by viewModels()

    /** 普通行: 标题/副标题/目标Activity/点击动作 */
    private data class SettingsRow(
        val titleRes: Int,
        val summaryRes: Int = 0,
        val hasSummary: Boolean = true,
        val target: Class<*>? = null,
        val action: ((SettingsActivity) -> Unit)? = null,
    )

    /** 开关行: 标题/副标题/开关key/默认值 */
    private data class SwitchRow(
        val titleRes: Int,
        val summaryRes: Int = 0,
        val prefKey: String,
        val defaultValue: Boolean = false,
        val onToggle: ((Boolean) -> Unit)? = null,
    )

    /** 分组数据 */
    private data class SettingsGroup(
        val headerRes: Int,
        val rows: List<Any>,  // SettingsRow or SwitchRow
    )

    /** 已创建的行 View 列表 */
    private val rowViews = mutableListOf<android.view.View>()

    /** 分组配置 */
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
                SwitchRow(
                    titleRes = R.string.settings_gnss_fallback,
                    summaryRes = R.string.settings_gnss_fallback_summary,
                    prefKey = "gnss_fallback_enabled",
                    defaultValue = true,
                ),
                SettingsRow(
                    titleRes = R.string.settings_data_refresh,
                    summaryRes = R.string.settings_data_refresh_summary,
                    action = { activity -> activity.showRefreshRateDialog() },
                ),
                SwitchRow(
                    titleRes = R.string.settings_trip_auto_record,
                    summaryRes = R.string.settings_trip_auto_record_summary,
                    prefKey = "trip_auto_record",
                    defaultValue = false,
                ),
                SettingsRow(
                    titleRes = R.string.settings_data_source,
                    summaryRes = R.string.settings_data_source_summary,
                ),
            ),
        ),
        SettingsGroup(
            headerRes = R.string.settings_group_notification,
            rows = listOf(
                SwitchRow(
                    titleRes = R.string.settings_notify_low_battery,
                    summaryRes = R.string.settings_notify_low_battery_summary,
                    prefKey = "notify_low_battery",
                    defaultValue = true,
                ),
                SwitchRow(
                    titleRes = R.string.settings_notify_charging,
                    summaryRes = R.string.settings_notify_charging_summary,
                    prefKey = "notify_charging",
                    defaultValue = true,
                ),
                SwitchRow(
                    titleRes = R.string.settings_notify_temperature,
                    summaryRes = R.string.settings_notify_temperature_summary,
                    prefKey = R.string.settings_notify_temperature.toString(),
                    defaultValue = false,
                ),
                SwitchRow(
                    titleRes = R.string.settings_notify_door,
                    summaryRes = R.string.settings_notify_door_summary,
                    prefKey = "notify_door",
                    defaultValue = true,
                ),
            ),
        ),
        SettingsGroup(
            headerRes = R.string.settings_group_security,
            rows = listOf(
                SwitchRow(
                    titleRes = R.string.settings_security_lock_confirm,
                    summaryRes = R.string.settings_security_lock_confirm_summary,
                    prefKey = "security_lock_confirm",
                    defaultValue = true,
                ),
                SwitchRow(
                    titleRes = R.string.settings_security_auto_lock,
                    summaryRes = R.string.settings_security_auto_lock_summary,
                    prefKey = "security_auto_lock",
                    defaultValue = false,
                ),
                SwitchRow(
                    titleRes = R.string.settings_security_nfc_auth,
                    summaryRes = R.string.settings_security_nfc_auth_summary,
                    prefKey = "security_nfc_auth",
                    defaultValue = false,
                ),
            ),
        ),
        SettingsGroup(
            headerRes = R.string.settings_group_advanced,
            rows = listOf(
                SwitchRow(
                    titleRes = R.string.settings_advanced_debug,
                    summaryRes = R.string.settings_advanced_debug_summary,
                    prefKey = "advanced_debug",
                    defaultValue = false,
                ),
                SettingsRow(
                    titleRes = R.string.settings_advanced_reset,
                    summaryRes = R.string.settings_advanced_reset_summary,
                    action = { activity -> activity.showResetDialog() },
                ),
                SettingsRow(
                    titleRes = R.string.settings_advanced_clear_cache,
                    summaryRes = R.string.settings_advanced_clear_cache_summary,
                    action = { activity -> activity.clearCache() },
                ),
                SettingsRow(
                    titleRes = R.string.settings_advanced_export_raw,
                    summaryRes = R.string.settings_advanced_export_raw_summary,
                    action = { activity -> activity.exportRawData() },
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
                when (row) {
                    is SettingsRow -> {
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
                    is SwitchRow -> {
                        val switchBinding = ItemSettingsSwitchBinding.inflate(inflater, container, false)
                        switchBinding.tvRowTitle.setText(row.titleRes)
                        if (row.summaryRes != 0) {
                            switchBinding.tvRowSummary.setText(row.summaryRes)
                        } else {
                            switchBinding.tvRowSummary.visibility = android.view.View.GONE
                        }
                        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                        switchBinding.switchRow.isChecked = prefs.getBoolean(row.prefKey, row.defaultValue)
                        switchBinding.switchRow.setOnCheckedChangeListener { _, isChecked ->
                            prefs.edit().putBoolean(row.prefKey, isChecked).apply()
                            row.onToggle?.invoke(isChecked)
                        }
                        switchBinding.root.setOnClickListener {
                            switchBinding.switchRow.toggle()
                        }
                        container.addView(switchBinding.root)
                        rowViews.add(switchBinding.root)
                    }
                }
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
        if (rowViews.size < 18) return

        rowViews[0].findViewById<TextView>(R.id.tvRowSummary)
            .setText(if (state.isPaired) R.string.settings_paired else R.string.settings_not_paired)

        rowViews[1].findViewById<TextView>(R.id.tvRowSummary)
            .text = themeDisplayName(state.themeMode)

        rowViews[2].findViewById<TextView>(R.id.tvRowSummary)
            .setText(
                if (state.unitSystem == "imperial") R.string.settings_unit_imperial
                else R.string.settings_unit_metric
            )

        rowViews[3].findViewById<TextView>(R.id.tvRowSummary)
            .text = languageDisplayName(state.appLanguage)

        rowViews[17].findViewById<TextView>(R.id.tvRowSummary)
            .text = getString(R.string.settings_version_format, com.tesla.dashboard.BuildConfig.VERSION_NAME)
    }

    private fun showRefreshRateDialog() {
        val rates = arrayOf("1 秒", "2 秒", "5 秒", "10 秒")
        val rateValues = intArrayOf(1000, 2000, 5000, 10000)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val currentRate = prefs.getInt("refresh_rate", 1000)
        val currentIndex = rateValues.indexOf(currentRate).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_data_refresh)
            .setSingleChoiceItems(rates, currentIndex) { dialog, which ->
                prefs.edit().putInt("refresh_rate", rateValues[which]).apply()
                rowViews[5].findViewById<TextView>(R.id.tvRowSummary).text = rates[which]
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showResetDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_advanced_reset)
            .setMessage("确定要重置所有设置吗？此操作不可撤销。")
            .setPositiveButton("重置") { _, _ ->
                getSharedPreferences("settings", MODE_PRIVATE).edit().clear().apply()
                Toast.makeText(this, "设置已重置", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearCache() {
        cacheDir.deleteRecursively()
        Toast.makeText(this, "缓存已清除", Toast.LENGTH_SHORT).show()
    }

    private fun exportRawData() {
        Toast.makeText(this, "导出原始数据功能开发中...", Toast.LENGTH_SHORT).show()
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
            child.findViewById<SwitchMaterial>(R.id.switchRow)?.apply {
                thumbTintList = ColorStateList.valueOf(c.accentGreen)
                trackTintList = ColorStateList.valueOf(c.divider)
            }
        }
    }
}
