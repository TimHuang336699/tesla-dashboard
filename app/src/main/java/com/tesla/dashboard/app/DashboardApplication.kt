package com.tesla.dashboard.app

import android.app.Application
import com.tesla.dashboard.util.ThemeManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application 入口类
 *
 * 使用 @HiltAndroidApp 注解触发 Hilt 的代码生成，
 * 自动创建并管理应用级别的依赖注入容器。
 *
 * 在 onCreate 中启动 [ThemeManager] 监听主题设置，
 * 实现日夜模式自动切换。
 */
@HiltAndroidApp
class DashboardApplication : Application() {

    /** 主题管理器，由 Hilt 自动注入 */
    @Inject
    lateinit var themeManager: ThemeManager

    override fun onCreate() {
        super.onCreate()

        // 启动主题监听，自动应用保存的主题模式
        themeManager.observeTheme()
    }
}
