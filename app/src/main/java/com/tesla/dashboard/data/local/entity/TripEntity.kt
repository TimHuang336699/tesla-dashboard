package com.tesla.dashboard.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 行程记录 Room 实体
 *
 * 对应数据模型 [com.tesla.dashboard.data.model.Trip],存储于 "trips" 表。
 * 字段与 Trip 模型一一对应,通过 Room 持久化到 SQLite。
 *
 * @property id 主键,自增(新建记录时传 0,Room 会自动分配)
 * @property startTime 行程开始时间 epoch millis
 * @property endTime 行程结束时间 epoch millis(行程进行中时为 0)
 * @property startLat 起点纬度
 * @property startLon 起点经度
 * @property endLat 终点纬度
 * @property endLon 终点经度
 * @property distanceKm 行程总里程 km
 * @property durationSec 行程总时长 秒
 * @property avgSpeed 平均速度 km/h
 * @property maxSpeed 最高速度 km/h
 * @property avgConsumption 平均电耗 kWh/100km(null = 无 Tesla 数据)
 * @property startSOC 起始电量百分比(null = Tesla 未连接)
 * @property endSOC 结束电量百分比(null = Tesla 未连接)
 * @property trackPointsJson 轨迹点 JSON 序列化(GPS 点列表)
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 行程开始时间 epoch millis */
    val startTime: Long,

    /** 行程结束时间 epoch millis(行程进行中时为 0) */
    val endTime: Long,

    /** 起点纬度 */
    val startLat: Double,

    /** 起点经度 */
    val startLon: Double,

    /** 终点纬度 */
    val endLat: Double,

    /** 终点经度 */
    val endLon: Double,

    /** 行程总里程 km */
    val distanceKm: Float,

    /** 行程总时长 秒 */
    val durationSec: Long,

    /** 平均速度 km/h */
    val avgSpeed: Float,

    /** 最高速度 km/h */
    val maxSpeed: Float,

    /** 平均电耗 kWh/100km(null = 无 Tesla 数据) */
    val avgConsumption: Float?,

    /** 起始电量百分比(null = Tesla 未连接) */
    val startSOC: Int?,

    /** 结束电量百分比(null = Tesla 未连接) */
    val endSOC: Int?,

    /** 轨迹点 JSON 序列化(GPS 点列表) */
    val trackPointsJson: String,
)
