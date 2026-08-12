package com.tesla.dashboard

import com.tesla.dashboard.util.VersionUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VersionUtils 语义化版本比较测试 (v0.5.3)
 */
class VersionUtilsTest {

    @Test
    fun compare_equal() {
        assertEquals(0, VersionUtils.compare("0.5.2", "0.5.2"))
        assertEquals(0, VersionUtils.compare("1.0.0", "1.0.0"))
    }

    @Test
    fun compare_ordering() {
        assertTrue(VersionUtils.compare("0.5.3", "0.5.2") > 0)
        assertTrue(VersionUtils.compare("0.5.2", "0.5.10") < 0) // 逐段数字, 非字典序
        assertTrue(VersionUtils.compare("1.0.0", "0.9.9") > 0)
        assertTrue(VersionUtils.compare("0.10.0", "0.9.9") > 0)
        assertTrue(VersionUtils.compare("0.5.2", "0.5.2") == 0)
    }

    @Test
    fun compare_partialSegments() {
        // 缺段视作 0
        assertEquals(0, VersionUtils.compare("0.5", "0.5.0"))
        assertTrue(VersionUtils.compare("0.5", "0.5.1") < 0)
        assertTrue(VersionUtils.compare("0.5.1", "0.5") > 0)
    }

    @Test
    fun compare_ignoresPreReleaseAndBuild() {
        assertEquals(0, VersionUtils.compare("1.0.0-beta", "1.0.0"))
        assertEquals(0, VersionUtils.compare("1.0.0+build5", "1.0.0"))
        assertEquals(0, VersionUtils.compare("0.5.2-alpha.1", "0.5.2"))
    }

    @Test
    fun compare_handlesNonNumeric() {
        // 非数字段被忽略
        assertEquals(0, VersionUtils.compare("v0.5.2", "0.5.2"))
        assertEquals(0, VersionUtils.compare("0.5.x", "0.5.0"))
    }

    @Test
    fun meetsMinimum() {
        assertTrue(VersionUtils.meetsMinimum("0.5.3", null))       // 无限制
        assertTrue(VersionUtils.meetsMinimum("0.5.3", "0.5.2"))    // 高于要求
        assertTrue(VersionUtils.meetsMinimum("0.5.2", "0.5.2"))    // 恰好满足
        assertFalse(VersionUtils.meetsMinimum("0.5.1", "0.5.2"))   // 低于要求
        assertFalse(VersionUtils.meetsMinimum("0.4.9", "0.5.0"))
        assertTrue(VersionUtils.meetsMinimum("1.0.0", "0.5.2"))    // 跨大版本
    }
}
