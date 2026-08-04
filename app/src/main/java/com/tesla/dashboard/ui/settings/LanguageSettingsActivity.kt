package com.tesla.dashboard.ui.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
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
     * 直接给三个 RadioButton 绑定点击事件 (不依赖 RadioGroup 回调,
     * 避免程序化 check() 触发干扰), 点击立即:
     * 1. setLanguage 同步完成 (DataStore 写入 + 静态缓存更新 + 系统联动)
     * 2. recreate() 重建本页, attachBaseContext 读到新缓存即新语言
     * 失败时 Toast 提示 (异常可见, 不静默失效)。
     */
    private fun setupRadioListener() {
        binding.rbLanguageZh.setOnClickListener {
            changeLanguage("zh")
        }
        binding.rbLanguageEn.setOnClickListener {
            changeLanguage("en")
        }
        binding.rbLanguageSystem.setOnClickListener {
            changeLanguage("system")
        }
    }

    /**
     * 切换语言并重建页面
     *
     * 已处于目标语言则跳过 (用静态缓存 [LanguageManager.currentLanguage] 判断,
     * 不依赖表单填充时序, 点击必定生效; 异常通过 Toast 可见)。
     *
     * @param code 语言代码 ("system"/"zh"/"en")
     */
    private fun changeLanguage(code: String) {
        // 已处于目标语言: 跳过 (点击当前已选中项属正常无变化)
        if (code == LanguageManager.currentLanguage) return
        lifecycleScope.launch {
            try {
                languageManager.setLanguage(code)
                recreate()
            } catch (e: Exception) {
                android.util.Log.w("LangDebug", "changeLanguage failed: ${e.message}")
                Toast.makeText(
                    this@LanguageSettingsActivity,
                    R.string.settings_language_switch_failed,
                    Toast.LENGTH_SHORT,
                ).show()
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
