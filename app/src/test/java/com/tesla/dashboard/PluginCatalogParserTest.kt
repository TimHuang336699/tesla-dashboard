package com.tesla.dashboard

import com.tesla.dashboard.plugin.market.PluginCatalogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plugin-catalog.json 解析测试 (v0.5.3)
 *
 * 样例数据参照 docs/PLUGIN_CATALOG.md 规范。
 */
class PluginCatalogParserTest {

    @Test
    fun parse_validCatalog() {
        val json = """
            {
              "version": 1,
              "plugins": [
                {
                  "id": "sentry-mode",
                  "name": "Sentry Mode 快捷开关",
                  "description": "一键开关哨兵模式（需车辆支持）",
                  "version": "1.0.0",
                  "category": "ble_command",
                  "experimental": true,
                  "minAppVersion": "0.5.2",
                  "downloadUrl": "https://example.com/sentry-mode.apk"
                },
                {
                  "id": "trip-stats",
                  "name": "行程统计",
                  "description": "行程数据分析与导出",
                  "version": "0.3.0",
                  "category": "utility"
                }
              ]
            }
        """.trimIndent()

        val plugins = PluginCatalogParser.parse(json)
        assertEquals(2, plugins.size)

        val sentry = plugins[0]
        assertEquals("sentry-mode", sentry.id)
        assertEquals("Sentry Mode 快捷开关", sentry.name)
        assertEquals("1.0.0", sentry.version)
        assertTrue(sentry.experimental)
        assertEquals("0.5.2", sentry.minAppVersion)
        assertEquals("https://example.com/sentry-mode.apk", sentry.downloadUrl)
        assertTrue(sentry.categoryEnum != null)

        val trip = plugins[1]
        assertEquals("trip-stats", trip.id)
        assertFalse(trip.experimental)
        assertNull(trip.minAppVersion)
        assertNull(trip.downloadUrl)
    }

    @Test
    fun parse_emptyPlugins() {
        val plugins = PluginCatalogParser.parse("""{"version": 1, "plugins": []}""")
        assertTrue(plugins.isEmpty())
    }

    @Test
    fun parse_skipsBrokenEntries() {
        // 缺 id 的条目被跳过, 其余保留
        val json = """
            {
              "version": 1,
              "plugins": [
                {"name": "missing-id", "version": "1.0.0", "category": "utility"},
                {"id": "ok-plugin", "name": "OK", "version": "1.0.0", "category": "utility"},
                {"id": "missing-name", "version": "1.0.0", "category": "utility"}
              ]
            }
        """.trimIndent()

        val plugins = PluginCatalogParser.parse(json)
        assertEquals(1, plugins.size)
        assertEquals("ok-plugin", plugins[0].id)
    }

    @Test
    fun parse_rejectsUnsupportedVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginCatalogParser.parse("""{"version": 2, "plugins": []}""")
        }
    }

    @Test
    fun parse_rejectsInvalidJson() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginCatalogParser.parse("not json at all")
        }
    }

    @Test
    fun parse_missingPluginsField() {
        // 无 plugins 字段 → 视为空列表
        assertTrue(PluginCatalogParser.parse("""{"version": 1}""").isEmpty())
    }

    @Test
    fun parse_defaults() {
        // 可选字段缺失时取默认值
        val json = """
            {"version": 1, "plugins": [{"id": "min", "name": "Minimal", "category": "ble_command"}]}
        """.trimIndent()
        val plugin = PluginCatalogParser.parse(json)[0]
        assertEquals("0.0.0", plugin.version)
        assertFalse(plugin.experimental)
        assertNull(plugin.minAppVersion)
        assertNull(plugin.downloadUrl)
        assertEquals("", plugin.description)
    }
}
