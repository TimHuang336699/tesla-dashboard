package com.tesla.dashboard.app

import android.app.Application
import com.tesla.dashboard.BuildConfig
import com.tesla.dashboard.util.AppLog
import com.tesla.dashboard.util.LanguageManager
import com.tesla.dashboard.util.ThemeManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application 入口类
 *
 * 使用 @HiltAndroidApp 注解触发 Hilt 的代码生成，
 * 自动创建并管理应用级别的依赖注入容器。
 *
 * 在 onCreate 中启动 [ThemeManager] 监听主题设置和
 * [LanguageManager] 监听语言设置，实现无重启切换。
 */
@HiltAndroidApp
class DashboardApplication : Application() {

    /** 主题管理器，由 Hilt 自动注入 */
    @Inject
    lateinit var themeManager: ThemeManager

    /** 语言管理器，由 Hilt 自动注入 */
    @Inject
    lateinit var languageManager: LanguageManager

    /** 屏幕状态跟踪器 (v0.5.2 耗电优化: 熄屏降频轮询) */
    @Inject
    lateinit var screenStateTracker: com.tesla.dashboard.util.ScreenStateTracker

    /** 插件管理器 (v0.5.2 插件系统) */
    @Inject
    lateinit var pluginManager: com.tesla.dashboard.plugin.PluginManager

    override fun onCreate() {
        super.onCreate()

        // 记录启动环境 (诊断日志开头)
        AppLog.d(
            "AppStart",
            "version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "systemLocale=${resources.configuration.locales[0]} " +
                "sdk=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MODEL}",
        )

        // 启动主题监听，自动应用保存的主题模式
        themeManager.observeTheme()

        // 同步应用已保存的语言 (首帧即正确, 不闪切)
        languageManager.applyStoredLanguageSync()

        // 监听语言设置，变化时自动应用 (无需重启)
        languageManager.observeLanguage()

        // 启动屏幕状态监听 (熄屏时 BLE 轮询降频, 节省电量)
        screenStateTracker.start()

        // 初始化插件系统 (注册已启用的插件)
        pluginManager.init()
    }
}
