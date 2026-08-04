package com.tesla.dashboard.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.tesla.dashboard.data.local.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用语言管理器 — 应用内多语言切换
 *
 * 采用官方推荐方案:
 * - [AppCompatDelegate.setApplicationLocales] 作为应用内语言唯一入口
 * - Manifest 中的 [androidx.appcompat.app.AppLocalesMetadataHolderService]
 *   (autoStoreLocales=true) 负责 API 26-32 冷启动恢复语言
 * - DataStore 作为单一事实源, 默认 "system"(跟随系统)
 *
 * ## 语言代码
 * - "system": 跟随系统 (清空应用语言覆盖)
 * - "zh": 中文
 * - "en": English
 *
 * ## 使用方式
 * 1. 在 Application onCreate 中调用 [observeLanguage] 开始监听
 * 2. 设置页保存语言后, 本管理器自动调用 setApplicationLocales
 * 3. 启动时调用 [initReconcile] 做一次性对账, 保持系统设置与 DataStore 一致
 *
 * @param context 应用上下文
 * @param settingsRepository 设置仓库
 */
@Singleton
class LanguageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    /** 应用级协程作用域 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 开始监听语言设置并自动应用
     *
     * 在 Application.onCreate 中调用。
     * 收集 [SettingsRepository.appLanguageFlow], 变化时调用
     * [AppCompatDelegate.setApplicationLocales] 应用语言。
     */
    fun observeLanguage() {
        scope.launch {
            settingsRepository.appLanguageFlow.collect { language ->
                applyLanguage(language)
            }
        }
    }

    /**
     * 启动对账 — 保持系统设置与 DataStore 一致
     *
     * 官方文档"边界情况": 用户可能在系统设置中修改了应用语言,
     * 或在系统设置中清空了应用语言。启动时一次性同步:
     * - 系统设置清空了应用语言而 DataStore 仍非 "system" → 回写 "system"
     * - 系统设置改了语言而 DataStore 仍是 "system" → 同步 DataStore
     *
     * 在 Application.onCreate 中调用 (observeLanguage 之后)。
     */
    fun initReconcile() {
        scope.launch {
            // 当前系统实际生效的应用语言
            val currentSystemLocales = AppCompatDelegate.getApplicationLocales()
            val currentTag = currentSystemLocales.toLanguageTags()
            val currentLang = when {
                currentTag.contains("zh") -> "zh"
                currentTag.contains("en") -> "en"
                else -> "system"
            }

            val stored = settingsRepository.appLanguageFlow.first()

            when {
                // 系统设置为空(跟随系统)但存储非 system → 回写
                currentLang == "system" && stored != "system" -> {
                    settingsRepository.saveAppLanguage("system")
                }
                // 存储为 system 但系统实际已应用某语言 → 同步存储
                stored == "system" && currentLang != "system" -> {
                    settingsRepository.saveAppLanguage(currentLang)
                }
            }
        }
    }

    /**
     * 根据语言代码应用语言
     *
     * @param language 语言代码 ("system"/"zh"/"en")
     */
    private fun applyLanguage(language: String) {
        val locales = when (language) {
            "zh" -> LocaleListCompat.forLanguageTags("zh")
            "en" -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList() // 跟随系统
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
