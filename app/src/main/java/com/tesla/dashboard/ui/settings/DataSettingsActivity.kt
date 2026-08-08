package com.tesla.dashboard.ui.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tesla.dashboard.R
import com.tesla.dashboard.databinding.ActivitySubSettingsBinding
import com.tesla.dashboard.databinding.ItemSettingsRowBinding
import com.tesla.dashboard.databinding.ItemSettingsSectionHeaderBinding
import com.tesla.dashboard.databinding.ItemSettingsSwitchBinding
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.ThemeColors
import dagger.hilt.android.AndroidEntryPoint

/**
 * 数据设置二级页面
 */
@AndroidEntryPoint
class DataSettingsActivity : BaseImmersiveActivity() {

    private lateinit var binding: ActivitySubSettingsBinding
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.text = getString(R.string.settings_group_data)
        populateSettings()
        observeThemeColors()
        applyThemeColors(themeManager.colors.value)
    }

    private fun populateSettings() {
        val container = binding.rowsContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // GNSS 降级开关
        addSwitchRow(container, inflater,
            titleRes = R.string.settings_gnss_fallback,
            summaryRes = R.string.settings_gnss_fallback_summary,
            prefKey = "gnss_fallback_enabled",
            defaultValue = true
        )

        // 数据刷新频率
        addClickRow(container, inflater,
            titleRes = R.string.settings_data_refresh,
            summaryRes = 0,
            summaryText = getRefreshRateText(),
            onClick = { showRefreshRateDialog() }
        )

        // 自动记录行程
        addSwitchRow(container, inflater,
            titleRes = R.string.settings_trip_auto_record,
            summaryRes = R.string.settings_trip_auto_record_summary,
            prefKey = "trip_auto_record",
            defaultValue = false
        )

        // 数据源优先级 (显示当前值)
        addClickRow(container, inflater,
            titleRes = R.string.settings_data_source,
            summaryRes = R.string.settings_data_source_summary
        )
    }

    private fun getRefreshRateText(): String {
        val rate = prefs.getInt("refresh_rate", 1000)
        return when (rate) {
            1000 -> "1 秒"
            2000 -> "2 秒"
            5000 -> "5 秒"
            10000 -> "10 秒"
            else -> "1 秒"
        }
    }

    private fun showRefreshRateDialog() {
        val rates = arrayOf("1 秒", "2 秒", "5 秒", "10 秒")
        val rateValues = intArrayOf(1000, 2000, 5000, 10000)
        val currentRate = prefs.getInt("refresh_rate", 1000)
        val currentIndex = rateValues.indexOf(currentRate).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_data_refresh)
            .setSingleChoiceItems(rates, currentIndex) { dialog, which ->
                prefs.edit().putInt("refresh_rate", rateValues[which]).apply()
                populateSettings()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addSwitchRow(container: android.widget.LinearLayout, inflater: LayoutInflater,
                             titleRes: Int, summaryRes: Int, prefKey: String, defaultValue: Boolean) {
        val binding = ItemSettingsSwitchBinding.inflate(inflater, container, false)
        binding.tvRowTitle.setText(titleRes)
        binding.tvRowSummary.setText(summaryRes)
        binding.switchRow.isChecked = prefs.getBoolean(prefKey, defaultValue)
        binding.switchRow.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(prefKey, isChecked).apply()
        }
        binding.root.setOnClickListener { binding.switchRow.toggle() }
        container.addView(binding.root)
    }

    private fun addClickRow(container: android.widget.LinearLayout, inflater: LayoutInflater,
                            titleRes: Int, summaryRes: Int = 0, summaryText: String? = null, onClick: (() -> Unit)? = null) {
        val binding = ItemSettingsRowBinding.inflate(inflater, container, false)
        binding.tvRowTitle.setText(titleRes)
        if (summaryText != null) {
            binding.tvRowSummary.text = summaryText
        } else if (summaryRes != 0) {
            binding.tvRowSummary.setText(summaryRes)
        } else {
            binding.tvRowSummary.visibility = android.view.View.GONE
        }
        onClick?.let { binding.root.setOnClickListener { it() } }
        container.addView(binding.root)
    }

    override fun applyThemeColors(c: ThemeColors) {
        currentColors = c
        binding.rootLayout.setBackgroundColor(c.background)
        binding.btnBack.imageTintList = ColorStateList.valueOf(c.accentCyan)
        binding.tvTitle.setTextColor(c.textPrimary)
        val container = binding.rowsContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
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
