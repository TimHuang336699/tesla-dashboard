package com.tesla.dashboard.util

/**
 * 语义化版本号比较工具 (v0.5.3)
 *
 * 支持 x.y.z (z 可省略) 格式, 逐段按数字比较。
 * 忽略 pre-release/build 元数据后缀 (如 "1.0.0-beta" 视作 "1.0.0")。
 */
object VersionUtils {

    /**
     * 比较两个版本号
     *
     * @return >0 表示 a 较新, <0 表示 b 较新, 0 表示相同
     */
    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /**
     * 当前版本是否满足最低版本要求
     *
     * @param current 当前应用版本
     * @param minimum 最低要求版本, null 表示无限制
     * @return true=兼容
     */
    fun meetsMinimum(current: String, minimum: String?): Boolean =
        minimum == null || compare(current, minimum) >= 0

    /**
     * 解析版本号为数字段列表
     *
     * "0.5.2" → [0, 5, 2]; 常见 "v" 前缀被去除;
     * 非数字段 (如 "x") 忽略。
     */
    private fun parse(version: String): List<Int> {
        var cleaned = version.trim().substringBefore("-").substringBefore("+")
        cleaned = cleaned.removePrefix("v").removePrefix("V")
        return cleaned.split(".").mapNotNull { it.toIntOrNull() }
    }
}
