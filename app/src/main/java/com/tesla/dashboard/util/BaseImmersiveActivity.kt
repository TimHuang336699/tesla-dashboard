package com.tesla.dashboard.util

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * 全屏沉浸式 + 主题管理基类
 *
 * 抽取各页面重复的:
 * - 全屏沉浸式窗口配置 (内容延伸到系统栏/屏幕常亮/隐藏系统栏)
 * - [ThemeManager] 注入与 [currentColors] 缓存
 * - 主题颜色流收集 ([observeThemeColors]), 变化时回调 [applyThemeColors]
 *
 * ## 使用方式
 * 1. 子类继承本类 (Hilt 的 @AndroidEntryPoint 由基类提供)
 * 2. 实现 [applyThemeColors] 应用配色
 * 3. onCreate 中调用 [observeThemeColors] 开始收集主题流
 *
 * @see ThemeManager
 * @see ThemeColors
 */
@AndroidEntryPoint
abstract class BaseImmersiveActivity : AppCompatActivity() {

    /** 主题管理器,由 Hilt 自动注入 */
    @Inject
    lateinit var themeManager: ThemeManager

    /** 当前主题颜色 (由主题流更新, 供 UI 在数据变化时复用) */
    protected var currentColors: ThemeColors = ThemeColors.Dark

    /**
     * 应用当前语言到 Activity 上下文
     *
     * 在系统注入布局资源之前强制应用 [LanguageManager.currentLanguage]
     * (静态缓存, 任何时序可读):
     * - "system" → 保持系统语言
     * - "zh"/"en" → 通过 [createConfigurationContext] 生成带指定语言的环境,
     *   后续 getString / 资源查找全部走该环境
     *
     * @param newBase 原始上下文
     */
    override fun attachBaseContext(newBase: Context) {
        val language = LanguageManager.currentLanguage
        val base = if (language != LanguageManager.DEFAULT_LANGUAGE) {
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(Locale.forLanguageTag(language))
            newBase.createConfigurationContext(config)
        } else {
            newBase
        }
        super.attachBaseContext(base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupImmersiveMode()
    }

    /**
     * 配置全屏沉浸式模式
     *
     * - [WindowCompat.setDecorFitsSystemWindows](false): 内容延伸到系统栏区域
     * - [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]: 屏幕常亮(仪表盘场景必需)
     * - 允许内容延伸到刘海/挖孔区域 (Android P+)
     * - [WindowInsetsControllerCompat.hide]: 隐藏状态栏和导航栏
     * - [BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]: 滑动边缘时短暂显示系统栏后自动隐藏
     */
    protected fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * 观察主题颜色流 — 实时应用配色 (无需重建 Activity)
     *
     * 在 onCreate 中调用。收集 [ThemeManager.colors], 变化时更新
     * [currentColors] 并回调 [applyThemeColors]。
     */
    protected fun observeThemeColors() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                themeManager.colors.collect { colors ->
                    currentColors = colors
                    applyThemeColors(colors)
                }
            }
        }
    }

    /**
     * 应用主题颜色到页面所有 UI 元素
     *
     * 由子类实现, 在主题流发射新值时调用。
     *
     * @param c 当前主题颜色集合
     */
    protected abstract fun applyThemeColors(c: ThemeColors)
}
