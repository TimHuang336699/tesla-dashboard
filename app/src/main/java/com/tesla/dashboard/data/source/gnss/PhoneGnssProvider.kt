package com.tesla.dashboard.data.source.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.tesla.dashboard.data.model.DataSource
import com.tesla.dashboard.data.model.VehicleData
import com.tesla.dashboard.data.source.VehicleDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 手机 GNSS 降级数据源 (v0.5.0)
 *
 * 基于 FusedLocationProviderClient, 在 Tesla BLE 不可用时提供
 * 车速/位置/航向/海拔等运动学字段, 保证仪表盘不空白、行程里程不中断。
 *
 * ## 工作方式
 * - 由 [VehicleDataRepository] 根据 BLE 可用性驱动 [setEnabled]:
 *   BLE 断开/失败时启用 (1s 定位), BLE 恢复时停用, 省电。
 * - 行程距离: 仅在被启用期间从定位点间距累加 (起点为 0),
 *   仓库以"BLE 基线 + GNSS 增量"方式续接, 不会重复累计。
 * - [isGnssActive] = true 表示当前帧来自真实定位。
 *
 * ## 与 v0.3 旧 GNSS 数据源的区别
 * - 不再是独立数据源, 而是 BLE 的降级补充 (BLE 为主数据源)
 * - 增加 [setEnabled] 开关, 避免无谓的常驻定位耗电
 *
 * @param context 应用级 Context (用于权限检查)
 */
@Singleton
class PhoneGnssProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : VehicleDataSource {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    /** GNSS 是否已获得定位 (收到首个有效定位后置为 true) */
    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: Flow<Boolean> = _isAvailable.asStateFlow()

    /** 降级开关状态流 — 由仓库根据 BLE 可用性驱动, 数据流实时响应 */
    private val _enabled = MutableStateFlow(false)

    /** 上一次有效定位, 用于累加行程距离 */
    @Volatile
    private var lastLocation: Location? = null

    /** 本次启用期间累计行程距离(米), 启用时从 0 开始 */
    @Volatile
    private var tripDistanceMeters: Float = 0f

    /**
     * 设置降级开关 (v0.5.0)
     *
     * 由 [com.tesla.dashboard.data.repository.VehicleDataRepository] 调用:
     * - BLE 不可用 → true: 开始/恢复定位, 行程累计从 0 重新开始
     * - BLE 恢复 → false: 停止定位, 避免常驻耗电
     *
     * 注意: 开关值通过 [MutableStateFlow] 实时生效 — 数据流在收集期间
     * 也会响应开关变化 (flatMapLatest), 而不是仅在流启动时检查一次。
     *
     * @param enabled true=启用 GNSS 降级
     */
    fun setEnabled(enabled: Boolean) {
        if (_enabled.value == enabled) return
        _enabled.value = enabled
        lastLocation = null
        tripDistanceMeters = 0f
        if (!enabled) {
            _isAvailable.value = false
        }
    }

    /**
     * 重置行程累计 (仓库 resetTrip 时调用)
     */
    fun resetTrip() {
        lastLocation = null
        tripDistanceMeters = 0f
    }

    /**
     * 观察 GNSS 定位数据流
     *
     * 结构:
     * 1. 立即发射初始空帧 (isGnssActive=false), 保证仓库合并流不被阻塞
     * 2. flatMapLatest 跟随 [_enabled] 开关动态启停定位 —
     *    流启动后 BLE 才断开也能立即开始降级, BLE 恢复时立即停用
     * 3. 启用且有定位权限时以 1s 间隔请求高精度定位
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeData(): Flow<VehicleData> = flow {
        // 初始空帧 — combine 合并流需要每个源先发射一次
        emit(VehicleData(dataSource = DataSource.GNSS, isGnssActive = false))
        // 跟随开关动态启停 (修复: 原实现仅在流启动时检查一次 enabled)
        emitAll(
            _enabled.flatMapLatest { enabled ->
                if (enabled && hasLocationPermission()) {
                    locationFlow()
                } else {
                    emptyFlow()
                }
            },
        )
    }

    /**
     * 定位数据流 — 仅在被 [observeData] 的 flatMapLatest 激活期间存在
     */
    @SuppressLint("MissingPermission") // 调用方已检查 ACCESS_FINE_LOCATION 权限
    private fun locationFlow(): Flow<VehicleData> = callbackFlow {
        // 高精度定位请求, 更新间隔 1s
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS,
        ).apply {
            setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
        }.build()

        // 定位回调 — 将 LocationResult 转换为 VehicleData 并发送到 Flow
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(processLocation(location))
                }
            }
        }

        try {
            // 请求定位更新 (回调在主线程执行)
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper(),
            )
        } catch (e: SecurityException) {
            // 权限被回收等异常 — 静默停用, 不崩溃
            awaitClose { /* no-op */ }
            return@callbackFlow
        }

        // Flow 被取消 (开关关闭) 时移除定位回调, 释放资源
        awaitClose {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            } catch (_: Exception) { /* no-op */ }
        }
    }

    /**
     * 将 Location 对象转换为 VehicleData
     *
     * 只填充 GNSS 运动学字段 (车速/位置/航向/海拔/行程), 其余字段保持默认值。
     * 同时累加行程距离, 并过滤定位漂移 (距离过大视为跳变, 不计入行程)。
     */
    private fun processLocation(location: Location): VehicleData {
        // 累加行程距离: 计算与上一定位点之间的距离
        lastLocation?.let { prev ->
            val distance = location.distanceTo(prev)
            if (distance > 0f && distance < MAX_REASONABLE_DISTANCE_M) {
                tripDistanceMeters += distance
            }
        }
        lastLocation = location

        // 首个有效定位后标记已可用
        if (!_isAvailable.value) {
            _isAvailable.value = true
        }

        // speed: Location.getSpeed() 返回 m/s, 需转换为 km/h
        val speedKmh = if (location.hasSpeed()) location.speed * MS_TO_KMH else 0f
        // bearing: 无航向时返回 0
        val bearing = if (location.hasBearing()) location.bearing else 0f

        return VehicleData(
            dataSource = DataSource.GNSS,
            isGnssActive = true,
            speed = speedKmh,
            latitude = location.latitude,
            longitude = location.longitude,
            heading = bearing,
            altitude = location.altitude,
            tripDistance = tripDistanceMeters / METERS_PER_KM,
        )
    }

    /**
     * 检查是否拥有精确定位权限
     */
    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun start() {
        // 重置行程状态, 准备开始新的数据采集
        lastLocation = null
        tripDistanceMeters = 0f
        _isAvailable.value = false
    }

    override suspend fun stop() {
        // 停用降级, 释放定位资源
        setEnabled(false)
        _isAvailable.value = false
        lastLocation = null
    }

    companion object {
        /** 定位更新间隔(ms) */
        private const val UPDATE_INTERVAL_MS = 1_000L

        /** m/s 转 km/h 的换算系数 */
        private const val MS_TO_KMH = 3.6f

        /** 米转千米的换算系数 */
        private const val METERS_PER_KM = 1000f

        /** 两次定位间最大合理距离(米), 超过此值视为 GPS 跳变, 不计入行程 */
        private const val MAX_REASONABLE_DISTANCE_M = 200f
    }
}
