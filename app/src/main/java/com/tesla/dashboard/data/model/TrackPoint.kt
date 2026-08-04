package com.tesla.dashboard.data.model

import com.google.gson.annotations.SerializedName

/**
 * GPS 轨迹点
 *
 * 用于记录行程中的 GPS 位置点,序列化为 JSON 存储到 Room 数据库的 trackPointsJson 字段。
 *
 * 字段名使用简短别名(lat/lon/spd/ts/hdg),以减小 JSON 体积 —
 * 单次行程可能包含数千个点,缩写后可显著降低数据库行体积。
 *
 * @property latitude  纬度
 * @property longitude 经度
 * @property speed     速度 km/h
 * @property timestamp 时间戳 epoch millis
 * @property heading   航向 0-360 度
 */
data class TrackPoint(
    @SerializedName("lat") val latitude: Double,
    @SerializedName("lon") val longitude: Double,
    @SerializedName("spd") val speed: Float,        // km/h
    @SerializedName("ts") val timestamp: Long,       // epoch millis
    @SerializedName("hdg") val heading: Float,       // 0-360 degrees
)
