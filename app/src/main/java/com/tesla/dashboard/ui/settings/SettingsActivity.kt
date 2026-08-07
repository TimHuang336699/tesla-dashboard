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
 * 设置列表页 (一级页面) — 手机设置风格 (大分组 → 二级小项)
 *
 * 按功能分组, 每组带小号分组标题, 点击行进入对应二级详情页:
 *
 * - **车辆**: 蓝牙与车辆 → [BleSettingsActivity]
 * - **显示**: 显示与外观 → [AppearanceSettingsActivity]
 * - **通用**: 单位 → [UnitSettingsActivity]、语言 → [LanguageSettingsActivity]、
 *   导出诊断日志 (直达导出, 不跳转)
 * - **关于**: 关于 → [AboutActivity]
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

    /** 行数据: 标题/副标题(当前值)/目标 Activity/点击动作 */
    private data class SettingsRow(
        val titleRes: Int,
        val summaryRes: Int = 0,
        val hasSummary: Boolean = true,
        val target: Class<*>? = null,
        val action: ((SettingsActivity) -> Unit)? = null,
    )

    /** 分组数据: 标题 + 组内行 */
    private data class SettingsGroup(
        val headerRes: Int,
        val rows: List<SettingsRow>,
    )

    /** 已创建的行 View 列表 (与 [groups] 的行一一对应, 用于更新副标题) */
    private val rowViews = mutableListOf<android.view.View>()

    /** 分组配置 (手机设置风格: 大项分组 → 小项) */
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

        // 填充设置分组行
        populateGroups()

        // 观察主题流 — 实时应用配色
        observeThemeColors()
        applyThemeColors(themeManager.colors.value)

        // 观察列表状态 — 更新副标题
        observeListState()
    }

    /**
     * 动态填充分组 (每组: 分组标题 + 若干设置行)
     */
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

    /**
     * 观察列表状态, 更新各行副标题 (当前值)
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
     * 更新各行副标题为当前值 (通过 [rowViews] 定位, 与硬编码索引解耦)
     *
     * @param state 当前设置列表状态
     */
    private fun updateRowSummaries(state: SettingsListUiState) {
        if (rowViews.size < 6) return

        // 0: 蓝牙与车辆 — 已配对/未配对
        rowViews[0].findViewById<TextView>(R.id.tvRowSummary)
            .setText(if (state.isPaired) R.string.settings_paired else R.string.settings_not_paired)

        // 1: 显示与外观 — 当前主题名
        rowViews[1].findViewById<TextView>(R.id.tvRowSummary)
            .text = themeDisplayName(state.themeMode)

        // 2: 单位 — 公制/英制
        rowViews[2].findViewById<TextView>(R.id.tvRowSummary)
            .setText(
                if (state.unitSystem == "imperial") R.string.settings_unit_imperial
                else R.string.settings_unit_metric
            )

        // 3: 语言 — 跟随系统/中文/English
        rowViews[3].findViewById<TextView>(R.id.tvRowSummary)
            .text = languageDisplayName(state.appLanguage)

        // 5: 关于 — 版本号
        rowViews[5].findViewById<TextView>(R.id.tvRowSummary)
            .text = getString(R.string.settings_version_format, com.tesla.dashboard.BuildConfig.VERSION_NAME)
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

        // 更新所有分组标题与行的配色
        val container = binding.rowsContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.findViewById<TextView>(R.id.tvSectionTitle)
                ?.setTextColor(c.textSecondary)
            child.findViewById<TextView>(R.id.tvRowTitle)
                ?.setTextColor(c.textPrimary)
            child.findViewById<TextView>(R.id.tvRowSummary)
                ?.setTextColor(c.textSecondary)
            child.findViewById<TextView>(R.id.tvRowChevron)
                ?.setTextColor(c.textSecondary)
        }
    }
}
