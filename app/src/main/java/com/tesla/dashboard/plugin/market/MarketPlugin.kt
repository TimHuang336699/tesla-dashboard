package com.tesla.dashboard.plugin.market

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.tesla.dashboard.plugin.PluginCategory
import java.net.URI

/**
 * 市场插件信息 (v0.5.3 插件市场在线化)
 *
 * 对应 plugin-catalog.json (docs/PLUGIN_CATALOG.md) 中单个插件条目。
 *
 * @param id 全局唯一插件 ID (与 [com.tesla.dashboard.plugin.DashboardPlugin.id] 对应)
 * @param name 插件显示名称
 * @param description 插件描述
 * @param version 插件版本号
 * @param category 分类字符串 (`ble_command` / `utility`)
 * @param experimental 实验性标记
 * @param minAppVersion 最低应用版本 (语义化版本, null=无限制)
 * @param downloadUrl 插件 APK 下载地址
 */
data class MarketPluginInfo(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val category: String,
    val experimental: Boolean = false,
    val minAppVersion: String? = null,
    val downloadUrl: String? = null,
) {
    /** 分类映射 (未知分类 → null, UI 显示"其他") */
    val categoryEnum: PluginCategory?
        get() = when (category) {
            "ble_command" -> PluginCategory.BLE_COMMAND
            "utility" -> PluginCategory.UTILITY
            else -> null
        }

    /** 下载 URL 是否合法 (HTTPS + 有效主机) */
    val isDownloadUrlValid: Boolean
        get() = downloadUrl?.let { url ->
            runCatching {
                val uri = URI(url)
                uri.scheme == "https" && !uri.host.isNullOrBlank()
            }.getOrDefault(false)
        } ?: true // null URL is valid (no download available)
}

/**
 * plugin-catalog.json 解析器 (v0.5.3)
 *
 * 格式规范见 docs/PLUGIN_CATALOG.md。使用 Gson (纯 JVM 库, 可单元测试)。
 * 字段缺失或类型错误时按规范默认值处理, 单个插件条目损坏时跳过该条目 (容错)。
 */
object PluginCatalogParser {

    private const val CATALOG_VERSION = 1

    /**
     * 解析目录 JSON
     *
     * @param json plugin-catalog.json 文本
     * @return 插件列表 (损坏条目被跳过)
     * @throws IllegalArgumentException 顶层结构非法 (非对象 / 版本不支持 / JSON 损坏)
     */
    fun parse(json: String): List<MarketPluginInfo> {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: JsonSyntaxException) {
            throw IllegalArgumentException("Invalid catalog JSON", e)
        }
        if (!root.isJsonObject) {
            throw IllegalArgumentException("Catalog root must be a JSON object")
        }

        val rootObject = root.asJsonObject
        val version = rootObject.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
        if (version != CATALOG_VERSION) {
            throw IllegalArgumentException("Unsupported catalog version: $version")
        }

        val pluginsArray = rootObject.get("plugins")?.takeIf { it.isJsonArray } ?: return emptyList()
        val result = mutableListOf<MarketPluginInfo>()
        for (element in pluginsArray.asJsonArray) {
            if (!element.isJsonObject) continue
            parseItem(element.asJsonObject)?.let { result.add(it) }
        }
        return result
    }

    /**
     * 解析单个插件条目
     *
     * 必填字段缺失 → 返回 null (跳过该条目)。
     */
    private fun parseItem(item: com.google.gson.JsonObject): MarketPluginInfo? {
        val id = optString(item, "id").takeIf { it.isNotBlank() } ?: return null
        // 安全: 校验插件 ID 格式（仅允许字母、数字、连字符、下划线）
        if (!id.matches(Regex("^[a-zA-Z0-9_-]+$"))) return null
        val name = optString(item, "name").takeIf { it.isNotBlank() } ?: return null
        val description = optString(item, "description")
        val version = optString(item, "version").takeIf { it.isNotBlank() } ?: "0.0.0"
        val category = optString(item, "category")
        val experimental = item.get("experimental")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            ?: false
        val minAppVersion = optString(item, "minAppVersion").takeIf { it.isNotBlank() }
        val downloadUrl = optString(item, "downloadUrl").takeIf { it.isNotBlank() }
        // 安全: 校验下载 URL 必须为 HTTPS
        if (downloadUrl != null && !downloadUrl.startsWith("https://")) return null
        return MarketPluginInfo(
            id = id,
            name = name,
            description = description,
            version = version,
            category = category,
            experimental = experimental,
            minAppVersion = minAppVersion,
            downloadUrl = downloadUrl,
        )
    }

    /** 取字符串字段 (非字符串 / 缺失 → 空串) */
    private fun optString(obj: com.google.gson.JsonObject, key: String): String =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: ""
}
