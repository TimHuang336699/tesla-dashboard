package com.tesla.dashboard.ui.splash

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.tesla.dashboard.R
import com.tesla.dashboard.databinding.ActivitySplashBinding
import com.tesla.dashboard.ui.dashboard.DashboardActivity
import com.tesla.dashboard.util.AppLog
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.ThemeColors
import dagger.hilt.android.AndroidEntryPoint

/**
 * 启动页 Activity — 展示狐狸 logo 动画 1 秒后进入仪表盘
 *
 * 动画: 狐狸 logo 淡入 + 轻微放大 + 大小眼先后眨眼 (AnimatedVectorDrawable, ~1s)
 * - 播放完成后淡出并跳转 [DashboardActivity]
 * - API 31+ 由系统 SplashScreen 先行展示同一动画, 本页承接后立即跳转
 */
@AndroidEntryPoint
class SplashActivity : BaseImmersiveActivity() {

    /** ViewBinding 实例 */
    private lateinit var binding: ActivitySplashBinding

    /** 主线程 Handler, 用于控制跳转时机 */
    private val handler = Handler(Looper.getMainLooper())

    /** 动画总时长 (ms) */
    private val splashDurationMs = 1000L

    /** 跳转任务 */
    private val navigateTask = Runnable {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 启动狐狸动画
        val drawable = binding.ivFox.drawable
        if (drawable is Animatable) {
            drawable.start()
        }

        // 应用品牌配色 (启动页固定深蓝黑)
        applyThemeColors(ThemeColors.Dark)

        AppLog.d("Splash", "fox splash animation started, duration=${splashDurationMs}ms")

        // 动画播放期间隐藏应用名 (先展示 logo), 播放结束后淡入应用名
        binding.tvAppName.alpha = 0f

        // 1 秒后跳转
        handler.postDelayed(navigateTask, splashDurationMs)
    }

    override fun onDestroy() {
        handler.removeCallbacks(navigateTask)
        super.onDestroy()
    }

    /**
     * 启动页固定配色 (深蓝黑背景 + 白色文字)
     *
     * @param c 当前主题颜色集合 (启动页固定使用深色)
     */
    override fun applyThemeColors(c: ThemeColors) {
        binding.rootLayout.setBackgroundColor(0xFF1B1B2E.toInt())
        binding.tvAppName.setTextColor(0xFFFFFFFF.toInt())
    }
}
