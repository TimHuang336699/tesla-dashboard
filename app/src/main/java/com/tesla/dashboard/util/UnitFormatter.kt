package com.tesla.dashboard.util

import android.content.Context
import com.tesla.dashboard.R

/**
 * 单位格式化工具 — 公制/英制换算与展示
 *
 * `VehicleData` 内部统一公制 (km/h, km, °C), 本工具仅在 UI 格式化边界转换。
 * 换算规则:
 * - mph = km/h ÷ 1.609344
 * - mi = km ÷ 1.609344
 * - °F = °C × 9/5 + 32
 *
 * 同时提供速度表量程/刻度参数 (公制 0-240, 英制 0-160)。
 *
 * ## 使用方式
 * ```kotlin
 * val unitSystem = UnitSystem.fromCode(code)
 * binding.speedDisplay.setSpeed(UnitFormatter.speedValue(speedKmh, unitSystem))
 * binding.speedDisplay.unitText = UnitFormatter.speedUnit(context, unitSystem)
 * ```
 */
object UnitFormatter {

    /** 英里换算常数 (km per mile) */
    const val KM_PER_MILE = 1.609344f

    /** 公制速度表量程上限 km/h */
    const val MAX_SPEED_METRIC = 240f

    /** 英制速度表量程上限 mph */
    const val MAX_SPEED_IMPERIAL = 160f

    /** 主刻度间隔 (两套单位统一) */
    const val MAJOR_TICK_METRIC = 20
    const val MAJOR_TICK_IMPERIAL = 20

    /** 次刻度间隔 */
    const val MINOR_TICK_METRIC = 10
    const val MINOR_TICK_IMPERIAL = 10

    // ===== 数值换算 =====

    /**
     * 速度换算 (km/h → 目标单位)
     *
     * @param kmh 公制速度
     * @param system 目标单位系统
     * @return 目标单位的速度值
     */
    fun speedValue(kmh: Float, system: UnitSystem): Float {
        return if (system == UnitSystem.IMPERIAL) kmh / KM_PER_MILE else kmh
    }

    /**
     * 距离换算 (km → 目标单位)
     *
     * @param km 公制距离
     * @param system 目标单位系统
     * @return 目标单位的距离值
     */
    fun distanceValue(km: Float, system: UnitSystem): Float {
        return if (system == UnitSystem.IMPERIAL) km / KM_PER_MILE else km
    }

    /**
     * 温度换算 (°C → 目标单位)
     *
     * @param celsius 摄氏温度
     * @param system 目标单位系统
     * @return 目标单位的温度值 (保留整数)
     */
    fun temperatureValue(celsius: Float, system: UnitSystem): Int {
        return if (system == UnitSystem.IMPERIAL) {
            (celsius * 9f / 5f + 32f).toInt()
        } else {
            celsius.toInt()
        }
    }

    // ===== 速度表量程/刻度 =====

    /**
     * 目标单位系统的速度表量程上限
     */
    fun maxSpeed(system: UnitSystem): Float =
        if (system == UnitSystem.IMPERIAL) MAX_SPEED_IMPERIAL else MAX_SPEED_METRIC

    /**
     * 目标单位系统的主刻度间隔
     */
    fun majorTick(system: UnitSystem): Int =
        if (system == UnitSystem.IMPERIAL) MAJOR_TICK_IMPERIAL else MAJOR_TICK_METRIC

    /**
     * 目标单位系统的次刻度间隔
     */
    fun minorTick(system: UnitSystem): Int =
        if (system == UnitSystem.IMPERIAL) MINOR_TICK_IMPERIAL else MINOR_TICK_METRIC

    // ===== 单位字符串 =====

    /** 速度单位字符串 */
    fun speedUnit(context: Context, system: UnitSystem): String =
        context.getString(
            if (system == UnitSystem.IMPERIAL) R.string.unit_mph else R.string.unit_kmh
        )

    /** 距离单位字符串 */
    fun distanceUnit(context: Context, system: UnitSystem): String =
        context.getString(
            if (system == UnitSystem.IMPERIAL) R.string.unit_mi else R.string.unit_km
        )
}
