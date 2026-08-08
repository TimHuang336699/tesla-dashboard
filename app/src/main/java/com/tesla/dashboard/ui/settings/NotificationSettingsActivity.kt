package com.tesla.dashboard.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tesla.dashboard.R
import com.tesla.dashboard.databinding.ActivitySubSettingsBinding
import com.tesla.dashboard.databinding.ItemSettingsSwitchBinding
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.ThemeColors
import dagger.hilt.android.AndroidEntryPoint

/**
 * 通知设置二级页面
 */
@AndroidEntryPoint
class NotificationSettingsActivity : BaseImmersiveActivity() {

    private lateinit var binding: ActivitySubSettingsBinding
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.text = getString(R.string.settings_group_notification)
        populateSettings()
        observeThemeColors()
        applyThemeColors(themeManager.colors.value)
    }

    private fun populateSettings() {
        val container = binding.rowsContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        addSwitchRow(container, inflater,
            titleRes = R.string.settings_notify_low_battery,
            summaryRes = R.string.settings_notify_low_battery_summary,
            prefKey = "notify_low_battery",
            defaultValue = true
        )

        addSwitchRow(container, inflater,
            titleRes = R.string.settings_notify_charging,
            summaryRes = R.string.settings_notify_charging_summary,
            prefKey = "notify_charging",
            defaultValue = true
        )

        addSwitchRow(container, inflater,
            titleRes = R.string.settings_notify_temperature,
            summaryRes = R.string.settings_notify_temperature_summary,
            prefKey = "notify_temperature",
            defaultValue = false
        )

        addSwitchRow(container, inflater,
            titleRes = R.string.settings_notify_door,
            summaryRes = R.string.settings_notify_door_summary,
            prefKey = "notify_door",
            defaultValue = true
        )
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
            child.findViewById<SwitchMaterial>(R.id.switchRow)?.apply {
                thumbTintList = ColorStateList.valueOf(c.accentGreen)
                trackTintList = ColorStateList.valueOf(c.divider)
            }
        }
    }
}
