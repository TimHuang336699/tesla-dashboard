package com.tesla.dashboard.ui.settings

import android.content.res.ColorStateList
import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.tesla.dashboard.R
import com.tesla.dashboard.databinding.ActivityLanguageSettingsBinding
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.LanguageManager
import com.tesla.dashboard.util.ThemeColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 语言设置二级页 — 单选列表
 *
 * 选择后调用 [LanguageManager.setLanguage] 同步完成"保存 + 应用 + 更新缓存",
 * 随后 [recreate] 重建页面立即生效 (新 Activity 的 attachBaseContext 读到新缓存)。
 */
@AndroidEntryPoint
class LanguageSettingsActivity : BaseImmersiveActivity() {

    /** ViewBinding 实例 */
    private lateinit var binding: ActivityLanguageSettingsBinding

    /** 语言管理器, 由 Hilt 注入 (切换语言唯一入口) */
    @Inject
    lateinit var languageManager: LanguageManager

    /** 轻量设置 ViewModel (语言流) */
    private val viewModel: SettingsLightViewModel by viewModels()

    /** 表单是否已填充标记 */
    private var isFormPopulated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLanguageSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        setupRadioListener()

        // 观察主题流 — 实时应用配色
        observeThemeColors()
        applyThemeColors(themeManager.colors.value)

        // 观察已保存的语言设置 (填充选中项)
        observeSavedLanguage()
    }

    /**
     * 设置语言单选监听 — 选中即保存并重建
     *
     * 时序: setLanguage 同步完成 (DataStore 写入 + 缓存更新 + 系统联动),
     * 之后 recreate 触发本页重建, attachBaseContext 读到新缓存即新语言。
     */
    private fun setupRadioListener() {
        binding.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            if (!isFormPopulated) return@setOnCheckedChangeListener
            val code = when (checkedId) {
                R.id.rbLanguageZh -> "zh"
                R.id.rbLanguageEn -> "en"
                else -> "system"
            }
            lifecycleScope.launch {
                languageManager.setLanguage(code)
                recreate()
            }
        }
    }

    /**
     * 观察已保存的语言设置以填充选中项
     */
    private fun observeSavedLanguage() {
        lifecycleScope.launch {
            viewModel.appLanguageFlow.collect { language ->
                if (!isFormPopulated) {
                    binding.rgLanguage.check(
                        when (language) {
                            "zh" -> R.id.rbLanguageZh
                            "en" -> R.id.rbLanguageEn
                            else -> R.id.rbLanguageSystem
                        }
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

        binding.cardLanguage.strokeColor = c.divider
        binding.cardLanguage.setCardBackgroundColor(c.cardBackground)
        binding.tvSectionLanguage.setTextColor(c.accentCyan)

        val btnTint = ColorStateList.valueOf(c.accentCyan)
        listOf(
            binding.rbLanguageSystem,
            binding.rbLanguageZh,
            binding.rbLanguageEn,
        ).forEach { rb ->
            rb.buttonTintList = btnTint
            rb.setTextColor(c.textPrimary)
        }
    }
}
