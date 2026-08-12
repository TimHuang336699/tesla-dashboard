package com.tesla.dashboard.util

import android.util.Log

/**
 * 应用内诊断日志 — 环形缓冲 + 可导出
 *
 * 所有关键链路 (语言/主题/启动) 写入此处, 内存保留最近 [MAX_ENTRIES] 条。
 * 用户在设置-关于页可一键导出 (分享面板), 无需 adb 即可排查问题。
 *
 * 每条日志格式: "yyyy-MM-dd HH:mm:ss.SSS [级别] tag: message"
 */
object AppLog {

    /** 缓冲上限 (条) */
    private const val MAX_ENTRIES = 500

    /** 环形缓冲 (线程安全) */
    private val buffer = ArrayDeque<String>()

    private val lock = Any()

    /** 时间格式化 (仅主线程/串行访问, 使用简单拼接) */
    private fun timestamp(): String {
        val now = java.util.Calendar.getInstance()
        val sb = StringBuilder(23)
        sb.append(now.get(java.util.Calendar.YEAR)).append('-')
            .append(two(now.get(java.util.Calendar.MONTH) + 1)).append('-')
            .append(two(now.get(java.util.Calendar.DAY_OF_MONTH))).append(' ')
            .append(two(now.get(java.util.Calendar.HOUR_OF_DAY))).append(':')
            .append(two(now.get(java.util.Calendar.MINUTE))).append(':')
            .append(two(now.get(java.util.Calendar.SECOND))).append('.')
            .append(three(now.get(java.util.Calendar.MILLISECOND)))
        return sb.toString()
    }

    private fun two(v: Int): String = if (v < 10) "0$v" else v.toString()

    private fun three(v: Int): String = when {
        v < 10 -> "00$v"
        v < 100 -> "0$v"
        else -> v.toString()
    }

    /**
     * 写入 Debug 级日志 (同步输出 logcat + 内存缓冲)
     *
     * logcat 调用在单元测试环境 (android.util.Log 未 mock) 下自动降级,
     * 仅保留内存缓冲, 不影响生产行为。
     */
    fun d(tag: String, message: String) {
        runCatching { Log.d(tag, message) }
        append("D", tag, message)
    }

    /**
     * 写入 Warning 级日志
     */
    fun w(tag: String, message: String) {
        runCatching { Log.w(tag, message) }
        append("W", tag, message)
    }

    /**
     * 写入 Error 级日志 (含异常堆栈)
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        runCatching { Log.e(tag, message, throwable) }
        val stack = if (throwable != null) {
            runCatching { Log.getStackTraceString(throwable) }.getOrNull() ?: ""
        } else {
            ""
        }
        append("E", tag, if (stack.isBlank()) message else "$message\n$stack")
    }

    /**
     * 追加一条日志到环形缓冲
     */
    private fun append(level: String, tag: String, message: String) {
        val line = "${timestamp()} [$level] $tag: $message"
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_ENTRIES) {
                buffer.removeFirst()
            }
        }
    }

    /**
     * 导出全部缓冲日志 (按时间顺序)
     */
    fun dump(): String = synchronized(lock) { buffer.joinToString("\n") }

    /**
     * 清空缓冲
     */
    fun clear() {
        synchronized(lock) { buffer.clear() }
    }
}
