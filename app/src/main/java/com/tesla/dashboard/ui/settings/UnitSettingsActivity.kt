package com.tesla.dashboard.ui.settings

import android.content.res.ColorStateList
import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.tesla.dashboard.R
import com.tesla.dashboard.databinding.ActivityUnitSettingsBinding
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.ThemeColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 单位设置二级页 — 单选列表
 *
 * 选择后即时保存到 DataStore, 主界面/历史页通过
 * [com.tesla.dashboard.data.local.SettingsRepository.unitSystemFlow] 实时换算刷新。
 */
@AndroidEntryPoint
class UnitSettingsActivity : BaseImmersiveActivity() {

    /** ViewBinding 实例 */
    private lateinit var binding: ActivityUnitSettingsBinding

    /** 轻量设置 ViewModel (单位流) */
    private val viewModel: SettingsLightViewModel by viewModels()

    /** 表单是否已填充标记 */
    private var isFormPopulated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityUnitSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        setupRadioListener()

        // 观察主题流 — 实时应用配色
        observeThemeColors()
        applyThemeColors(themeManager.colors.value)

        // 观察已保存的单位设置 (填充选中项)
        observeSavedUnit()
    }

    /**
     * 设置单位单选监听 — 选中即保存
     */
    private fun setupRadioListener() {
        binding.rgUnit.setOnCheckedChangeListener { _, checkedId ->
            if (!isFormPopulated) return@setOnCheckedChangeListener
            val code = if (checkedId == R.id.rbUnitImperial) "imperial" else "metric"
            viewModel.saveUnitSystem(code)
        }
    }

    /**
     * 观察已保存的单位设置以填充选中项
     */
    private fun observeSavedUnit() {
        lifecycleScope.launch {
            viewModel.unitSystemFlow.collect { unitSystem ->
                if (!isFormPopulated) {
                    binding.rgUnit.check(
                        if (unitSystem == "imperial") R.id.rbUnitImperial else R.id.rbUnitMetric
                    )
                    isFormPopulated = true
                }
            }
        }
    }

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

        binding.cardUnit.strokeColor = c.divider
        binding.cardUnit.setCardBackgroundColor(c.cardBackground)
        binding.tvSectionUnit.setTextColor(c.accentCyan)

        val btnTint = ColorStateList.valueOf(c.accentCyan)
        listOf(
            binding.rbUnitMetric,
            binding.rbUnitImperial,
        ).forEach { rb ->
            rb.buttonTintList = btnTint
            rb.setTextColor(c.textPrimary)
        }
    }
}
