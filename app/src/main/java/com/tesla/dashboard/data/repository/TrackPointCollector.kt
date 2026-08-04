package com.tesla.dashboard.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tesla.dashboard.data.model.TrackPoint

/**
 * GPS 轨迹点收集器
 *
 * 在行程追踪期间收集 GPS 轨迹点,序列化为 JSON 字符串存储到 Room 数据库的
 * trackPointsJson 字段。
 *
 * ## 主要特性
 * - 使用 Gson 进行 JSON 序列化/反序列化
 * - 内部使用 [MutableList] 维护轨迹点列表
 * - 限制最大点数([maxPoints]),超过时按间隔采样压缩,保持点数上限稳定
 * - 线程安全:所有读写操作均在 [lock] 监视器内完成
 *
 * ## 点数压缩策略
 * 长行程可能产生远超 [maxPoints] 的 GPS 点。当点数达到上限时,执行"间隔采样压缩":
 * 1. 将现有列表按步长 2 重采样(保留索引 0、2、4... 的点),使列表长度减半;
 * 2. 将 [samplingInterval] 翻倍,后续新点也按新的间隔保留。
 *
 * 每次触发压缩后,采样间隔翻倍,等效于"每 N 个点保留一个"。这样无论行程多长,
 * 点数始终被约束在 [maxPoints] 附近,同时尽可能保留轨迹的空间分布特征。
 *
 * @param maxPoints 最大保留点数,达到后触发压缩
 */
class TrackPointCollector(
    private val maxPoints: Int = DEFAULT_MAX_POINTS,
) {

    /** 轨迹点列表 */
    private val points: MutableList<TrackPoint> = mutableListOf()

    /**
     * 当前采样间隔(每收到 [samplingInterval] 个点保留一个)。
     * 初始为 1,即保留全部点;每次压缩后翻倍。
     */
    private var samplingInterval = 1

    /** 自上次保留点以来的计数器 */
    private var counter = 0

    /** 同步锁,保证 [points]/[samplingInterval]/[counter] 的线程安全 */
    private val lock = Any()

    /**
     * 添加一个轨迹点
     *
     * 按 [samplingInterval] 决定是否保留该点(间隔保留,避免在长行程中点数无限增长)。
     * 当点数达到 [maxPoints] 时,触发 [downsample] 压缩。
     *
     * @param latitude  纬度
     * @param longitude 经度
     * @param speed     速度 km/h
     * @param timestamp 时间戳 epoch millis
     * @param heading   航向 0-360 度
     */
    fun addPoint(
        latitude: Double,
        longitude: Double,
        speed: Float,
        timestamp: Long,
        heading: Float,
    ) {
        synchronized(lock) {
            // 按当前采样间隔决定是否保留该点
            counter++
            if (counter < samplingInterval) {
                return
            }
            counter = 0

            points.add(
                TrackPoint(
                    latitude = latitude,
                    longitude = longitude,
                    speed = speed,
                    timestamp = timestamp,
                    heading = heading,
                )
            )

            // 达到上限,压缩现有列表并提升采样间隔
            if (points.size >= maxPoints) {
                downsample()
            }
        }
    }

    /**
     * 压缩轨迹点列表
     *
     * 保留偶数索引(0、2、4...)的点,使列表长度减半;
     * 同时将 [samplingInterval] 翻倍,后续新点也按新间隔保留。
     */
    private fun downsample() {
        val newSize = points.size / 2 + 1
        val sampled = ArrayList<TrackPoint>(newSize)
        var i = 0
        while (i < points.size) {
            sampled.add(points[i])
            i += 2
        }
        points.clear()
        points.addAll(sampled)
        samplingInterval *= 2
        counter = 0
    }

    /**
     * 获取当前已收集的轨迹点数量。
     */
    fun size(): Int = synchronized(lock) { points.size }

    /**
     * 序列化为 JSON 字符串
     *
     * @return 轨迹点数组的 JSON 字符串(如 "[]" 或 "[{...},{...}]")
     */
    fun toJson(): String = synchronized(lock) { gson.toJson(points) }

    /**
     * 清空已收集的轨迹点,重置采样状态。
     */
    fun clear() {
        synchronized(lock) {
            points.clear()
            samplingInterval = 1
            counter = 0
        }
    }

    companion object {
        /** 默认最大保留点数 */
        private const val DEFAULT_MAX_POINTS = 5000

        /** 复用 Gson 实例(Gson 线程安全) */
        private val gson = Gson()

        /**
         * 从 JSON 字符串反序列化轨迹点列表
         *
         * @param json 轨迹点数组的 JSON 字符串
         * @return 轨迹点列表;输入为空或格式错误时返回空列表
         */
        @JvmStatic
        fun fromJson(json: String?): List<TrackPoint> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val type = object : TypeToken<List<TrackPoint>>() {}.type
                gson.fromJson<List<TrackPoint>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
