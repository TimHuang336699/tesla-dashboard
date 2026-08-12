package com.tesla.dashboard.data.model

import java.io.Serializable

/**
 * 已配对车辆信息
 *
 * @param vin 车辆识别号 (17 位)
 * @param batteryModel 车型代码 (用于电量/能耗计算)
 * @param vehiclePublicKeyRaw 车辆 VCSEC 公钥 (65 字节未压缩格式, Base64)
 * @param pairedAt 配对时间戳 (毫秒)
 */
data class VehicleInfo(
    val vin: String,
    val batteryModel: String = "",
    val vehiclePublicKeyRaw: String = "",
    val pairedAt: Long = 0L,
) : Serializable

/**
 * 车辆列表序列化辅助
 */
object VehicleListSerializer {

    /**
     * 将车辆列表序列化为 JSON 字符串
     */
    fun toJson(vehicles: List<VehicleInfo>): String {
        if (vehicles.isEmpty()) return "[]"
        val sb = StringBuilder()
        sb.append('[')
        vehicles.forEachIndexed { index, v ->
            if (index > 0) sb.append(',')
            sb.append('{')
            sb.append("\"vin\":\"${escapeJson(v.vin)}\"")
            sb.append(",\"batteryModel\":\"${escapeJson(v.batteryModel)}\"")
            sb.append(",\"vehiclePublicKeyRaw\":\"${escapeJson(v.vehiclePublicKeyRaw)}\"")
            sb.append(",\"pairedAt\":${v.pairedAt}")
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * 将 JSON 字符串反序列化为车辆列表
     */
    fun fromJson(json: String): List<VehicleInfo> {
        val vehicles = mutableListOf<VehicleInfo>()
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return vehicles

        val inner = trimmed.removePrefix("[").removeSuffix("]")
        var i = 0
        while (i < inner.length) {
            val objStart = inner.indexOf('{', i)
            if (objStart == -1) break
            val objEnd = findMatchingBrace(inner, objStart)
            if (objEnd == -1) break

            val obj = inner.substring(objStart + 1, objEnd)
            vehicles.add(parseVehicleObject(obj))
            i = objEnd + 1
        }
        return vehicles
    }

    private fun parseVehicleObject(obj: String): VehicleInfo {
        var vin = ""
        var batteryModel = ""
        var vehiclePublicKeyRaw = ""
        var pairedAt = 0L

        val pairs = splitByComma(obj)
        for (pair in pairs) {
            val colonIdx = pair.indexOf(':')
            if (colonIdx == -1) continue
            val key = pair.substring(0, colonIdx).trim().removeSurrounding("\"")
            val value = pair.substring(colonIdx + 1).trim()
            when (key) {
                "vin" -> vin = value.removeSurrounding("\"")
                "batteryModel" -> batteryModel = value.removeSurrounding("\"")
                "vehiclePublicKeyRaw" -> vehiclePublicKeyRaw = value.removeSurrounding("\"")
                "pairedAt" -> pairedAt = value.toLongOrNull() ?: 0L
            }
        }
        return VehicleInfo(vin, batteryModel, vehiclePublicKeyRaw, pairedAt)
    }

    private fun findMatchingBrace(s: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun splitByComma(s: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in s.indices) {
            when (s[i]) {
                '{' -> depth++
                '}' -> depth--
                ',' -> if (depth == 0) {
                    parts.add(s.substring(start, i))
                    start = i + 1
                }
            }
        }
        if (start < s.length) parts.add(s.substring(start))
        return parts
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
