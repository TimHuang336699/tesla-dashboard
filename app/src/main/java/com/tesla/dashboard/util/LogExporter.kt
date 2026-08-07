package com.tesla.dashboard.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tesla.dashboard.BuildConfig
import com.tesla.dashboard.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志导出工具 (v0.4: 从 AboutActivity 抽取, 设置页/关于页共用)
 *
 * 将 [AppLog] 环形缓冲写入 cacheDir/logs 后经 FileProvider 分享,
 * 任意应用可接收 (微信/邮件等), 无需权限, 全版本可用。
 */
object LogExporter {

    /**
     * 导出诊断日志并弹出分享面板
     *
     * @param context 调用方 Context
     * @return 是否成功发起分享 (空日志返回 false 并提示)
     */
    fun export(context: Context): Boolean {
        // 追加导出上下文信息
        AppLog.d(
            "ExportLogs",
            "export started version=${BuildConfig.VERSION_NAME} " +
                "langCache=${LanguageManager.currentLanguage} " +
                "systemLocale=${context.resources.configuration.locales[0]}",
        )

        val content = AppLog.dump()
        if (content.isBlank()) {
            Toast.makeText(context, R.string.settings_logs_empty, Toast.LENGTH_SHORT).show()
            return false
        }

        return runCatching {
            // 1. 写入缓存文件
            val logsDir = File(context.cacheDir, "logs")
            logsDir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(logsDir, "tesla_dashboard_log_$stamp.txt")
            file.writeText(content, Charsets.UTF_8)

            // 2. FileProvider 分享
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_logs_share_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(share, context.getString(R.string.settings_export_logs)),
            )
            AppLog.d("ExportLogs", "shared file=${file.absolutePath}")
            true
        }.getOrElse { e ->
            AppLog.e("ExportLogs", "export FAILED: ${e.message}", e)
            Toast.makeText(
                context,
                context.getString(R.string.settings_logs_export_failed, e.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
            false
        }
    }
}
