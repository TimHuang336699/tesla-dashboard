package com.tesla.dashboard.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 屏幕开关状态跟踪器 (v0.5.2 耗电优化)
 *
 * 监听系统 [Intent.ACTION_SCREEN_ON] / [Intent.ACTION_SCREEN_OFF] 广播,
 * 暴露 [screenOn] StateFlow 供 BLE 轮询调度使用:
 * - 熄屏时用户无法看到仪表盘, 降低轮询频率 (30s) 节省电量
 * - 亮屏后立即恢复高频轮询
 *
 * 注册: 由 [com.tesla.dashboard.app.DashboardApplication] 调用 [start]。
 */
@Singleton
class ScreenStateTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _screenOn = MutableStateFlow(isScreenOn())
    val screenOn: StateFlow<Boolean> = _screenOn.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> _screenOn.value = true
                Intent.ACTION_SCREEN_OFF -> _screenOn.value = false
            }
        }
    }

    @Volatile
    private var started = false

    /**
     * 开始监听屏幕状态 (幂等)
     */
    fun start() {
        if (started) return
        started = true
        _screenOn.value = isScreenOn()
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            context.registerReceiver(receiver, filter)
        }
    }

    private fun isScreenOn(): Boolean =
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive ?: true
        }.getOrDefault(true)
}
