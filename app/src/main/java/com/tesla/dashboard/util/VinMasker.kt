package com.tesla.dashboard.util

/**
 * VIN 遮罩工具 — 配对完成后隐藏后 6 位
 *
 * 仅在显示层使用遮罩, 数据层始终持有明文 VIN。
 *
 * ## 规则
 * - 完整 17 位 VIN: 保留前 11 位, 后 6 位替换为 `*` (如 "5YJSA1E47HF00******")
 * - 不足 11 位: 全部替换为 `*` (防御性处理)
 * - 已含 `*` 的输入 (重复遮罩): 原样返回, 避免叠加
 */
object VinMasker {

    /** 保留的前缀长度 (17 - 6) */
    private const val KEEP_PREFIX_LENGTH = 11

    /** 隐藏位数 */
    private const val HIDDEN_LENGTH = 6

    /**
     * 将 VIN 后 6 位替换为 `*`
     *
     * @param vin 明文 VIN
     * @return 遮罩后的显示字符串
     */
    fun mask(vin: String): String {
        if (vin.contains('*')) return vin
        val keep = vin.take(KEEP_PREFIX_LENGTH)
        val masked = if (keep.isBlank()) vin else keep + "*".repeat(HIDDEN_LENGTH)
        return masked
    }

    /**
     * 判断字符串是否为遮罩形式 (含 `*`)
     *
     * @param vin 待判断字符串
     * @return true 表示已遮罩
     */
    fun isMasked(vin: String): Boolean = vin.contains('*')
}
