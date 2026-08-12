package com.tesla.dashboard.data.remote

import android.content.Context
import com.tesla.dashboard.plugin.market.MarketPluginInfo
import com.tesla.dashboard.plugin.market.PluginCatalogParser
import com.tesla.dashboard.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 插件市场目录仓库 (v0.5.3 插件市场在线化)
 *
 * 职责:
 * 1. 从远端 [CATALOG_URL] 拉取 plugin-catalog.json
 * 2. 成功后写入文件缓存 (cacheDir), 离线 / 拉取失败时回退缓存
 * 3. 提供 APK 下载能力 ([downloadApk], 供"安装"按钮调用)
 *
 * 动态加载外部插件 (DexClassLoader) 仍在安全审计中, 当前版本
 * 市场仅支持浏览 / 兼容性检查 / 下载 APK 文件。
 */
@Singleton
class PluginCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val TAG = "PluginCatalog"

    private val cacheFile: File get() = File(context.cacheDir, "plugin_catalog.json")

    /**
     * 拉取插件目录
     *
     * @param forceRefresh true=强制联网刷新; false=有缓存时直接返回缓存
     * @return 插件列表, 成功或缓存回退均视为成功; 完全失败时 [Result.failure]
     */
    suspend fun fetchCatalog(forceRefresh: Boolean): Result<List<MarketPluginInfo>> =
        withContext(Dispatchers.IO) {
            val cached = readCache()
            if (!forceRefresh && cached != null) {
                AppLog.d(TAG, "Catalog served from cache (${cached.size} plugins)")
                return@withContext Result.success(cached)
            }
            try {
                val json = downloadText(CATALOG_URL)
                val plugins = PluginCatalogParser.parse(json)
                writeCache(json)
                AppLog.d(TAG, "Catalog refreshed: ${plugins.size} plugins")
                Result.success(plugins)
            } catch (e: Exception) {
                AppLog.w(TAG, "Catalog fetch failed: ${e.message}")
                if (cached != null) {
                    AppLog.d(TAG, "Falling back to stale cache (${cached.size} plugins)")
                    Result.success(cached)
                } else {
                    Result.failure(e)
                }
            }
        }

    /**
     * 下载插件 APK
     *
     * @param downloadUrl 下载地址
     * @param fileName 保存文件名 (通常 <pluginId>.apk)
     * @return 下载后的文件
     * @throws IOException 网络失败 / 非 2xx / 写入失败
     */
    suspend fun downloadApk(downloadUrl: String, fileName: String): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "plugins").apply { mkdirs() }
            val target = File(dir, fileName)
            val conn = openConnection(downloadUrl)
            try {
                conn.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                AppLog.d(TAG, "APK downloaded: ${target.absolutePath} (${target.length()} bytes)")
            } finally {
                conn.disconnect()
            }
            target
        }

    /** 已下载插件文件 (不存在时返回 null) */
    fun downloadedApkFile(pluginId: String): File? {
        val file = File(File(context.filesDir, "plugins"), "$pluginId.apk")
        return file.takeIf { it.exists() }
    }

    private fun readCache(): List<MarketPluginInfo>? {
        if (!cacheFile.exists()) return null
        return runCatching { PluginCatalogParser.parse(cacheFile.readText()) }.getOrNull()
    }

    private fun writeCache(json: String) {
        runCatching { cacheFile.writeText(json) }
    }

    private fun downloadText(url: String): String {
        val conn = openConnection(url)
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code from $url")
        }
        return conn
    }

    companion object {
        /** 插件市场目录地址 (tesla-dashboard-plugins 仓库, 见 docs/PLUGIN_CATALOG.md) */
        const val CATALOG_URL =
            "https://raw.githubusercontent.com/tesla-dashboard-plugins/tesla-dashboard-plugins/main/plugin-catalog.json"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}
