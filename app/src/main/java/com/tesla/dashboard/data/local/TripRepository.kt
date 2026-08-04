package com.tesla.dashboard.data.local

import com.tesla.dashboard.data.local.dao.TripDao
import com.tesla.dashboard.data.local.entity.TripEntity
import com.tesla.dashboard.data.model.Trip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 行程记录仓库
 *
 * 包装 [TripDao],对外提供行程的创建、结束、查询等操作。
 * 内部负责 [TripEntity] ↔ [Trip] 的模型转换,隔离 Room 数据层与领域层。
 *
 * 使用 Hilt @Singleton 保证全局单例。
 *
 * @param tripDao 行程 DAO
 */
@Singleton
class TripRepository @Inject constructor(
    private val tripDao: TripDao,
) {

    /**
     * 开始一个新行程
     *
     * 创建一条行程记录(endTime 等结束字段暂为占位值),返回新记录 id。
     * 调用方应保存返回的 id,在行程结束时传入 [endTrip]。
     *
     * @param startTime 行程开始时间 epoch millis
     * @param startLat 起点纬度
     * @param startLon 起点经度
     * @param startSOC 起始电量百分比(可选,null = Tesla 未连接)
     * @return 新行程记录的 id
     */
    suspend fun startTrip(
        startTime: Long,
        startLat: Double,
        startLon: Double,
        startSOC: Int? = null,
    ): Long {
        val trip = TripEntity(
            startTime = startTime,
            endTime = 0L,
            startLat = startLat,
            startLon = startLon,
            endLat = 0.0,
            endLon = 0.0,
            distanceKm = 0f,
            durationSec = 0L,
            avgSpeed = 0f,
            maxSpeed = 0f,
            avgConsumption = null,
            startSOC = startSOC,
            endSOC = null,
            trackPointsJson = "[]",
        )
        return tripDao.insertTrip(trip)
    }

    /**
     * 结束行程
     *
     * 根据 tripId 查找已有记录,更新结束信息(终点位置 / 里程 / 时长 / 速度 / 电耗等)。
     * 若 tripId 不存在则直接返回,不做任何操作。
     *
     * @param tripId 行程 id(由 [startTrip] 返回)
     * @param endTime 行程结束时间 epoch millis
     * @param endLat 终点纬度
     * @param endLon 终点经度
     * @param distanceKm 行程总里程 km
     * @param durationSec 行程总时长 秒
     * @param avgSpeed 平均速度 km/h
     * @param maxSpeed 最高速度 km/h
     * @param avgConsumption 平均电耗 kWh/100km(null = 无 Tesla 数据)
     * @param endSOC 结束电量百分比(可选,null = Tesla 未连接)
     * @param trackPointsJson 轨迹点 JSON
     */
    suspend fun endTrip(
        tripId: Long,
        endTime: Long,
        endLat: Double,
        endLon: Double,
        distanceKm: Float,
        durationSec: Long,
        avgSpeed: Float,
        maxSpeed: Float,
        avgConsumption: Float?,
        endSOC: Int?,
        trackPointsJson: String,
    ) {
        val existing = tripDao.getTripByIdNow(tripId) ?: return

        val updated = existing.copy(
            endTime = endTime,
            endLat = endLat,
            endLon = endLon,
            distanceKm = distanceKm,
            durationSec = durationSec,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            avgConsumption = avgConsumption,
            endSOC = endSOC,
            trackPointsJson = trackPointsJson,
        )
        tripDao.updateTrip(updated)
    }

    /**
     * 获取所有行程(按开始时间倒序)
     *
     * @return 行程列表 [Flow],数据库数据变化时自动更新
     */
    fun getAllTrips(): Flow<List<Trip>> =
        tripDao.getAllTrips().map { entities -> entities.map { it.toTrip() } }

    /**
     * 根据 id 获取行程
     *
     * @param id 行程 id
     * @return 行程 [Flow],数据库数据变化时自动更新;不存在时发射 null
     */
    fun getTrip(id: Long): Flow<Trip?> =
        tripDao.getTripById(id).map { entity -> entity?.toTrip() }

    // ===== 模型转换 =====

    /**
     * [TripEntity] → [Trip] 领域模型转换
     */
    private fun TripEntity.toTrip(): Trip = Trip(
        id = id,
        startTime = startTime,
        endTime = endTime,
        startLat = startLat,
        startLon = startLon,
        endLat = endLat,
        endLon = endLon,
        distanceKm = distanceKm,
        durationSec = durationSec,
        avgSpeed = avgSpeed,
        maxSpeed = maxSpeed,
        avgConsumption = avgConsumption,
        startSOC = startSOC,
        endSOC = endSOC,
        trackPointsJson = trackPointsJson,
    )
}
