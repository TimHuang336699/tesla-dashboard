package com.tesla.dashboard.data.model

/**
 * 行程记录数据模型
 */
data class Trip(
    val id: Long = 0,
    val startTime: Long,        // epoch millis
    val endTime: Long,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val distanceKm: Float,      // 总里程
    val durationSec: Long,      // 总时长
    val avgSpeed: Float,        // 平均速度 km/h
    val maxSpeed: Float,        // 最高速度 km/h
    val avgConsumption: Float?, // 平均电耗 kWh/100km (null = 无 Tesla 数据)
    val startSOC: Int?,         // 起始电量
    val endSOC: Int?,           // 结束电量
    val trackPointsJson: String // 轨迹点 JSON 序列化(GPS 点列表)
)
