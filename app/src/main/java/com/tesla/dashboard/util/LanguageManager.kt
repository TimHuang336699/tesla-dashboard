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
import kotlinx.coroutines.runBlocking
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
 * ## 设计说明
 * 以 DataStore 为**唯一事实源**, 单向应用到系统:
 * `observeLanguage` 收集存储值并调用 [AppCompatDelegate.setApplicationLocales]。
 * 不做"反向对账"——启动早期 [AppCompatDelegate.getApplicationLocales] 尚未恢复,
 * 若据此回写 DataStore 会竞态清空用户已保存的语言选择 (历史 bug)。
 *
 * ## 使用方式
 * 在 Application onCreate 中调用 [observeLanguage] 开始监听,
 * 设置页保存语言后, 本管理器自动调用 setApplicationLocales 并重建 Activity。
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
     * 同步应用已保存的语言 — 在首个 Activity 创建前调用
     *
     * Application.onCreate 中同步读取 DataStore 首值并立即 setApplicationLocales,
     * 确保冷启动首帧即为正确语言, 不出现"先系统语言、再闪切"。
     * DataStore 首读为磁盘小文件 (<100ms), 对启动耗时影响可忽略。
     */
    fun applyStoredLanguageSync() {
        runBlocking {
            val language = settingsRepository.appLanguageFlow.first()
            applyLanguage(language)
        }
    }

    /**
     * 开始监听语言设置并自动应用
     *
     * 在 Application.onCreate 中调用 (applyStoredLanguageSync 之后)。
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
     * 根据语言代码应用语言
     *
     * 若系统当前应用语言已与目标一致则跳过, 避免无谓的 Activity 重建。
     *
     * @param language 语言代码 ("system"/"zh"/"en")
     */
    private fun applyLanguage(language: String) {
        val locales = when (language) {
            "zh" -> LocaleListCompat.forLanguageTags("zh")
            "en" -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList() // 跟随系统
        }

        // 无变化跳过: 防止切换后 recreate 重建收集时再次触发重建
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.toLanguageTags() == locales.toLanguageTags()) return

        AppCompatDelegate.setApplicationLocales(locales)
    }
}
