package com.tesla.dashboard.ui.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tesla.dashboard.R
import com.tesla.dashboard.databinding.ActivitySubSettingsBinding
import com.tesla.dashboard.databinding.ItemSettingsRowBinding
import com.tesla.dashboard.databinding.ItemSettingsSwitchBinding
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.ThemeColors
import dagger.hilt.android.AndroidEntryPoint

/**
 * 高级设置二级页面
 */
@AndroidEntryPoint
class AdvancedSettingsActivity : BaseImmersiveActivity() {

    private lateinit var binding: ActivitySubSettingsBinding
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.text = getString(R.string.settings_group_advanced)
        populateSettings()
        observeThemeColors()
        applyThemeColors(themeManager.colors.value)
    }

    private fun populateSettings() {
        val container = binding.rowsContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // 调试模式
        addSwitchRow(container, inflater,
            titleRes = R.string.settings_advanced_debug,
            summaryRes = R.string.settings_advanced_debug_summary,
            prefKey = "advanced_debug",
            defaultValue = false
        )

        // 重置所有设置
        addClickRow(container, inflater,
            titleRes = R.string.settings_advanced_reset,
            summaryRes = R.string.settings_advanced_reset_summary,
            onClick = { showResetDialog() }
        )

        // 清除缓存
        addClickRow(container, inflater,
            titleRes = R.string.settings_advanced_clear_cache,
            summaryRes = R.string.settings_advanced_clear_cache_summary,
            onClick = { clearCache() }
        )

        // 导出原始数据
        addClickRow(container, inflater,
            titleRes = R.string.settings_advanced_export_raw,
            summaryRes = R.string.settings_advanced_export_raw_summary,
            onClick = { exportRawData() }
        )
    }

    private fun showResetDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_advanced_reset)
            .setMessage("确定要重置所有设置吗？此操作不可撤销。")
            .setPositiveButton("重置") { _, _ ->
                prefs.edit().clear().apply()
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
                            titleRes: Int, summaryRes: Int, onClick: () -> Unit) {
        val binding = ItemSettingsRowBinding.inflate(inflater, container, false)
        binding.tvRowTitle.setText(titleRes)
        binding.tvRowSummary.setText(summaryRes)
        binding.root.setOnClickListener { onClick() }
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
