package com.tesla.dashboard.data.source.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.tesla.dashboard.data.model.VehicleData
import com.tesla.dashboard.data.source.VehicleDataSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GNSS 数据源 Provider — 基于 FusedLocationProviderClient
 *
 * 负责填充 VehicleData 中的 GNSS 相关字段:
 * speed, latitude, longitude, heading, altitude, tripDistance,
 * gpsAccuracy, speedAccuracy, isGpsLocked
 *
 * 定位配置:
 * - 优先级: Priority.PRIORITY_HIGH_ACCURACY
 *   (等同旧版 LocationRequest.PRIORITY_HIGH_ACCURACY)
 * - 更新间隔: 500ms
 *
 * 行程距离(tripDistance)通过累加相邻两次定位的距离实现,
 * 使用 Location.distanceTo 计算两点间距离,并过滤 GPS 跳变。
 *
 * Hilt 注入说明:
 * 本类通过 @Inject constructor 注入,供 Hilt 依赖图使用。
 * 实际接入 Hilt 时,需在构造参数上添加 @ApplicationContext 注解,
 * 并在提供此类的 Module 中添加 @Singleton 作用域,确保全局唯一实例。
 * 此处按需求未添加 Hilt 专属注解(@ApplicationContext / @Singleton 等)。
 */
@Singleton
class GnssProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : VehicleDataSource {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    /** GNSS 是否已锁定(收到首个有效定位后置为 true) */
    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: Flow<Boolean> = _isAvailable.asStateFlow()

    /** 上一次有效定位,用于累加行程距离 */
    @Volatile
    private var lastLocation: Location? = null

    /** 本次行程累计距离(米) */
    @Volatile
    private var tripDistanceMeters: Float = 0f

    /**
     * 观察 GNSS 定位数据流
     *
     * 使用 callbackFlow 将 FusedLocationProviderClient 的 LocationCallback
     * 转换为 Kotlin Flow。当 Flow 被收集时开始定位,取消收集时自动停止定位。
     */
    @SuppressLint("MissingPermission") // 调用方需确保已获取 ACCESS_FINE_LOCATION 运行时权限
    override fun observeData(): Flow<VehicleData> = callbackFlow {
        // 构建高精度定位请求,更新间隔 500ms
        // Priority.PRIORITY_HIGH_ACCURACY 等同旧版 LocationRequest.PRIORITY_HIGH_ACCURACY
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS
        ).apply {
            // 设置最小更新间隔,确保以 500ms 频率回调
            setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
        }.build()

        // 定位回调 — 将 LocationResult 转换为 VehicleData 并发送到 Flow
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val vehicleData = processLocation(location)
                    trySend(vehicleData)
                }
            }
        }

        // 请求定位更新(回调在主线程执行)
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        // Flow 被取消时移除定位回调,释放资源
        awaitClose {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    /**
     * 将 Location 对象转换为 VehicleData
     *
     * 只填充 GNSS 相关字段,其余字段保持 VehicleData 的默认值。
     * 同时累加行程距离,并在首次有效定位后标记 GPS 已锁定。
     */
    private fun processLocation(location: Location): VehicleData {
        // 累加行程距离: 计算与上一定位点之间的距离
        lastLocation?.let { prev ->
            val distance = location.distanceTo(prev)
            // 过滤 GPS 跳变(距离过大视为漂移,不计入行程)
            if (distance > 0f && distance < MAX_REASONABLE_DISTANCE_M) {
                tripDistanceMeters += distance
            }
        }
        lastLocation = location

        // 首个有效定位后标记 GPS 已锁定
        if (!_isAvailable.value) {
            _isAvailable.value = true
        }

        // speed: Location.getSpeed() 返回 m/s,需转换为 km/h
        val speedKmh = location.speed * MS_TO_KMH

        // speedAccuracyMetersPerSecond: API 26+ 可用,返回 m/s,转换为 km/h
        val speedAccuracyKmh = if (location.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond * MS_TO_KMH
        } else {
            0f
        }

        return VehicleData(
            speed = speedKmh,
            latitude = location.latitude,
            longitude = location.longitude,
            heading = location.bearing,
            altitude = location.altitude,
            tripDistance = tripDistanceMeters / METERS_PER_KM,
        )
    }

    /**
     * 使用 Haversine 公式计算两个经纬度坐标之间的球面距离(米)
     *
     * 作为 Location.distanceTo 的替代方案,适用于仅持有经纬度
     * 而无 Location 对象的场景(如从数据库读取历史轨迹点)。
     *
     * @param lat1 起点纬度(度)
     * @param lon1 起点经度(度)
     * @param lat2 终点纬度(度)
     * @param lon2 终点经度(度)
     * @return 球面距离(米)
     */
    private fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val earthRadiusM = 6_371_000.0 // 地球平均半径(米)

        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLon = Math.toRadians(lon2 - lon1)

        val a = sin(deltaLat / 2.0).let { it * it } +
            cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2.0).let { it * it }
        val c = 2 * asin(sqrt(a))

        return earthRadiusM * c
    }

    override suspend fun start() {
        // 重置行程状态,准备开始新的数据采集
        lastLocation = null
        tripDistanceMeters = 0f
        _isAvailable.value = false
    }

    override suspend fun stop() {
        // 标记数据源不可用,清理定位状态
        _isAvailable.value = false
        lastLocation = null
    }

    companion object {
        /** 定位更新间隔(ms) */
        private const val UPDATE_INTERVAL_MS = 500L

        /** m/s 转 km/h 的换算系数 */
        private const val MS_TO_KMH = 3.6f

        /** 米转千米的换算系数 */
        private const val METERS_PER_KM = 1000f

        /** 两次定位间最大合理距离(米),超过此值视为 GPS 跳变,不计入行程 */
        private const val MAX_REASONABLE_DISTANCE_M = 200f
    }
}
