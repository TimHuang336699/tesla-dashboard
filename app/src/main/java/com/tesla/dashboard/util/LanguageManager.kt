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
 * ## 双保险机制
 * 1. **显式缓存 + Activity 级应用**: [currentLanguage] 缓存当前语言,
 *    每个 Activity 在 [android.app.Activity.attachBaseContext] 中通过
 *    [createConfigurationContext] 强制应用, 不依赖任何库的隐式行为。
 * 2. **系统级联动**: 调用 [AppCompatDelegate.setApplicationLocales],
 *    让 Android 13+ 系统设置中的应用语言选项与进程级 locale 同步
 *    (所有异常被吞掉, 不影响主链路)。
 *
 * ## 语言代码
 * - "system": 跟随系统 (默认)
 * - "zh": 中文
 * - "en": English
 *
 * ## 使用方式
 * 1. Application.onCreate 调用 [applyStoredLanguageSync] (同步设缓存)
 *    和 [observeLanguage] (监听后续变化)。
 * 2. 设置页切换语言调用 [setLanguage] (挂起: 保存 + 应用 + 更新缓存),
 *    随后 Activity 显式 [android.app.Activity.recreate] 重建。
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
     * 当前语言代码缓存 ("system"/"zh"/"en")
     *
     * 由 [applyLanguage] 更新, 供各 Activity 的 attachBaseContext 同步读取。
     */
    @Volatile
    var currentLanguage: String = DEFAULT_LANGUAGE
        private set

    /**
     * 同步应用已保存的语言 — 在首个 Activity 创建前调用
     *
     * Application.onCreate 中同步读取 DataStore 首值并立即应用,
     * 确保冷启动首帧即为正确语言。DataStore 首读为磁盘小文件 (<100ms),
     * 对启动耗时影响可忽略; 异常被捕获, 不影响应用启动。
     */
    fun applyStoredLanguageSync() {
        runCatching {
            runBlocking { settingsRepository.appLanguageFlow.first() }
        }.onSuccess { language ->
            applyLanguage(language)
        }
    }

    /**
     * 开始监听语言设置并自动应用
     *
     * 在 Application.onCreate 中调用 (applyStoredLanguageSync 之后)。
     * 收集 [SettingsRepository.appLanguageFlow], 变化时调用
     * [AppCompatDelegate.setApplicationLocales] 应用语言。
     * collect 体内异常已被 [applyLanguage] 捕获, 不会终止监听。
     */
    fun observeLanguage() {
        scope.launch {
            settingsRepository.appLanguageFlow.collect { language ->
                applyLanguage(language)
            }
        }
    }

    /**
     * 保存并应用语言 — 设置页切换语言的唯一入口
     *
     * 同步完成"写入 DataStore + 更新缓存 + 系统级应用",
     * 返回后调用方可安全 [android.app.Activity.recreate],
     * 重建时 attachBaseContext 读到的新缓存即为目标语言。
     *
     * @param language 语言代码 ("system"/"zh"/"en")
     */
    suspend fun setLanguage(language: String) {
        settingsRepository.saveAppLanguage(language)
        applyLanguage(language)
    }

    /**
     * 根据语言代码应用语言
     *
     * 1. 更新 [currentLanguage] 缓存 (attachBaseContext 依赖)
     * 2. 调用 setApplicationLocales 与系统联动 (异常吞掉, 不终止监听)
     *
     * @param language 语言代码 ("system"/"zh"/"en")
     */
    private fun applyLanguage(language: String) {
        currentLanguage = language
        runCatching {
            val locales = when (language) {
                "zh" -> LocaleListCompat.forLanguageTags("zh")
                "en" -> LocaleListCompat.forLanguageTags("en")
                else -> LocaleListCompat.getEmptyLocaleList() // 跟随系统
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    companion object {
        /** 默认语言: 跟随系统 */
        const val DEFAULT_LANGUAGE = "system"
    }
}
