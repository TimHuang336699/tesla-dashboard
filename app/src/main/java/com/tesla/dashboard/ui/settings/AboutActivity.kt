package com.tesla.dashboard.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tesla.dashboard.BuildConfig
import com.tesla.dashboard.R
import com.tesla.dashboard.databinding.ActivityAboutSettingsBinding
import com.tesla.dashboard.util.AppLog
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.ThemeColors
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 关于设置二级页 — 版本信息 + 导出诊断日志
 *
 * 展示应用名称、版本号与说明; 提供"导出诊断日志"按钮,
 * 将 [AppLog] 缓冲写入 cacheDir 后经 FileProvider 分享
 * (微信/邮件等任意应用, 无需权限, 全版本可用)。
 */
@AndroidEntryPoint
class AboutActivity : BaseImmersiveActivity() {

    /** ViewBinding 实例 */
    private lateinit var binding: ActivityAboutSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAboutSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // 版本号
        binding.tvVersion.text = getString(R.string.settings_version_format, BuildConfig.VERSION_NAME)

        // 导出诊断日志
        binding.btnExportLogs.setOnClickListener { exportLogs() }

        // 观察主题流 — 实时应用配色
        observeThemeColors()
        applyThemeColors(themeManager.colors.value)
    }

    /**
     * 导出诊断日志
     *
     * 1. 追加本次导出头部 (版本/时间/当前语言缓存/系统语言)
     * 2. 写入 cacheDir/logs/tesla_dashboard_log_yyyyMMdd_HHmmss.txt
     * 3. FileProvider 授权后 ACTION_SEND 分享
     */
    private fun exportLogs() {
        // 追加导出上下文信息
        AppLog.d(
            "ExportLogs",
            "export started version=${BuildConfig.VERSION_NAME} " +
                "langCache=${com.tesla.dashboard.util.LanguageManager.currentLanguage} " +
                "systemLocale=${resources.configuration.locales[0]}",
        )

        val content = AppLog.dump()
        if (content.isBlank()) {
            Toast.makeText(this, R.string.settings_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            // 1. 写入缓存文件
            val logsDir = File(cacheDir, "logs")
            logsDir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(logsDir, "tesla_dashboard_log_$stamp.txt")
            file.writeText(content, Charsets.UTF_8)

            // 2. FileProvider 分享
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file,
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_logs_share_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.settings_export_logs)))
            AppLog.d("ExportLogs", "shared file=${file.absolutePath}")
        }.onFailure { e ->
            AppLog.e("ExportLogs", "export FAILED: ${e.message}", e)
            Toast.makeText(
                this,
                getString(R.string.settings_logs_export_failed, e.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * 应用主题颜色
     *
     * @param c 当前主题颜色集合
     */
    override fun applyThemeColors(c: ThemeColors) {
        currentColors = c

        binding.rootScroll.setBackgroundColor(c.background)
        binding.btnBack.imageTintList = ColorStateList.valueOf(c.accentCyan)
        binding.tvTitle.setTextColor(c.textPrimary)

        binding.cardAbout.strokeColor = c.divider
        binding.cardAbout.setCardBackgroundColor(c.cardBackground)
        binding.tvAppName.setTextColor(c.textPrimary)
        binding.tvVersion.setTextColor(c.textSecondary)
        binding.tvAboutDesc.setTextColor(c.textSecondary)

        binding.btnExportLogs.backgroundTintList = ColorStateList.valueOf(c.accentCyan)
    }
}
