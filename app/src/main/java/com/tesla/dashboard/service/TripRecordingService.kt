package com.tesla.dashboard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint

/**
 * 行程记录前台服务
 *
 * 在行程记录期间作为前台服务运行,保证应用在后台时仍能持续记录 GPS 轨迹。
 *
 * ## 前台服务类型
 * AndroidManifest.xml 中声明了 `android:foregroundServiceType="location"`,
 * 对应 [ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION]。
 * 需要 FOREGROUND_SERVICE 和 FOREGROUND_SERVICE_LOCATION 权限(已在 Manifest 中声明)。
 *
 * ## 通知
 * 使用低优先级通知通道(IMPORTANCE_LOW),避免在行程记录期间发出声音或振动,
 * 仅在状态栏显示持续通知,告知用户正在记录行程。
 *
 * ## 生命周期
 * - [onCreate]: 创建通知渠道
 * - [onStartCommand]: 启动前台通知,返回 START_STICKY 保证服务被杀后自动重启
 * - 由调用方(DashboardActivity)通过 stopService() 停止
 *
 * @AndroidEntryPoint 使 Hilt 可在此 Service 中进行依赖注入。
 */
@AndroidEntryPoint
class TripRecordingService : Service() {

    companion object {
        /** 通知渠道 ID */
        private const val CHANNEL_ID = "trip_recording_channel"

        /** 前台通知 ID(需 > 0) */
        private const val NOTIFICATION_ID = 1001
    }

    /**
     * 服务创建时调用,创建低优先级通知渠道。
     */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * 处理 startService / startForegroundService 调用。
     *
     * 创建前台通知并调用 [startForeground] 将服务提升为前台状态。
     * 返回 [START_STICKY],使服务在系统资源不足被杀后能自动重启。
     *
     * @param intent 启动 Intent(可携带额外参数,当前未使用)
     * @param flags 启动标志
     * @param startId 本次启动的唯一 ID
     * @return [START_STICKY]
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()

        // Android 14 (API 34) 需要显式指定 foregroundServiceType
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    /**
     * 不支持绑定,返回 null。
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 创建通知渠道(仅 API 26+ 需要,本项目 minSdk=26)。
     *
     * 渠道使用 [NotificationManager.IMPORTANCE_LOW]:
     * - 不发出声音
     * - 不弹出浮动通知
     * - 仅在状态栏显示图标和通知文本
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.tesla.dashboard.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(com.tesla.dashboard.R.string.notification_channel_desc)
            setShowBadge(false)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * 构建前台服务通知。
     *
     * - 设置为持续通知(setOngoing),用户无法手动滑除
     * - 使用低优先级,避免打扰用户
     * - 使用系统位置图标作为小图标(实际项目应替换为应用专属图标)
     *
     * @return 构建好的 [Notification]
     */
    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(com.tesla.dashboard.R.string.notification_title))
            .setContentText(getString(com.tesla.dashboard.R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
