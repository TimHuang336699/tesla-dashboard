package com.tesla.dashboard.ui.settings

import android.content.res.ColorStateList
import android.os.Bundle
import com.tesla.dashboard.BuildConfig
import com.tesla.dashboard.R
import com.tesla.dashboard.databinding.ActivityAboutSettingsBinding
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.ThemeColors
import dagger.hilt.android.AndroidEntryPoint

/**
 * 关于设置二级页 — 版本信息
 *
 * 展示应用名称、版本号与说明。
 */
@AndroidEntryPoint
class AboutActivity : BaseImmersiveActivity() {

    /** ViewBinding 实例 */
    private lateinit var binding: ActivityAboutSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAboutSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // 版本号
        binding.tvVersion.text = getString(R.string.settings_version_format, BuildConfig.VERSION_NAME)

        // 观察主题流 — 实时应用配色
        observeThemeColors()
        applyThemeColors(themeManager.colors.value)
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

        binding.cardAbout.strokeColor = c.divider
        binding.cardAbout.setCardBackgroundColor(c.cardBackground)
        binding.tvAppName.setTextColor(c.textPrimary)
        binding.tvVersion.setTextColor(c.textSecondary)
        binding.tvAboutDesc.setTextColor(c.textSecondary)
    }
}
