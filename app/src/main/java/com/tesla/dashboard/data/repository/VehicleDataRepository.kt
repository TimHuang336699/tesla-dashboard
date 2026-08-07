package com.tesla.dashboard.data.repository

import com.tesla.dashboard.data.model.DataSource
import com.tesla.dashboard.data.model.VehicleData
import com.tesla.dashboard.data.source.VehicleDataSource
import com.tesla.dashboard.data.source.ble.TeslaBleProvider
import com.tesla.dashboard.data.source.gnss.PhoneGnssProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 车辆数据统一仓库 — Tesla BLE 为主数据源, 手机 GNSS 为降级数据源 (v0.5.0)
 *
 * ## 双数据源合并策略 (v0.5.0)
 *
 * [observeVehicleData] 合并 BLE 与 GNSS 两个数据源的数据流:
 * - **BLE 可用** (isTeslaConnected && !isDataStale): 透传 BLE 数据,
 *   并记录行程基线 [lastBleTripKm]
 * - **BLE 失效** (轮询失败/断开): 用手机 GNSS 填充运动学字段
 *   (车速/位置/航向/海拔), 行程里程以 "BLE 基线 + GNSS 增量" 续接,
 *   保证仪表盘不空白、行程里程不中断
 *
 * GNSS 降级开关由 [start] 中订阅 BLE 可用性驱动:
 * BLE 不可用时才启用定位 (1s 间隔), BLE 恢复时立即停用, 省电。
 *
 * ## 数据来源
 * - **车速**: 车辆 CAN 总线实时车速 (Infotainment 域 DriveState)
 * - **加速度**: 由车速变化率计算 (纵向) + 航向变化率×车速 (横向)
 * - **海拔**: 车辆 GPS 模块提供 (Infotainment 域 DriveState.elevation)
 * - **行程里程**: BLE 时段由总里程表差值累加, GNSS 时段由定位距离差累加
 * - **瞬时电耗**: 由 SOC 变化 + 里程差值计算 (VehicleData.computeConsumption)
 * - **位置/航向**: 车辆 GPS 模块提供 (Infotainment 域 DriveState)
 * - **电池/温度/档位**: Infotainment 域 ChargeState/ClimateState/DriveState
 *
 * ## 数据刷新频率
 * - BLE: 5s 轮询 (行驶中 2.5s), 连续失败指数退避 (10s→20s→30s 封顶)
 * - GNSS 降级: 1s 定位更新 (仅 BLE 失效期间)
 *
 * ## 电耗计算
 * 内部维护 [prevData] 缓存, 保存上一次的车辆数据。
 * 调用方可通过 [getPrevData] 获取缓存值, 配合 [VehicleData.computeConsumption] 计算瞬时电耗。
 *
 * @param teslaBleProvider Tesla BLE 数据源 (主数据源)
 * @param gnssProvider 手机 GNSS 数据源 (降级数据源)
 */
@Singleton
class VehicleDataRepository @Inject constructor(
    @Named("tesla") private val teslaBleProvider: VehicleDataSource,
    @Named("gnss") private val gnssProvider: VehicleDataSource,
) {

    /**
     * 上一次的车辆数据缓存,用于电耗计算(瞬时/区间)。
     * 使用 @Volatile 保证多线程可见性。
     */
    @Volatile
    private var prevData: VehicleData? = null

    /** 常驻作用域 (GNSS 降级开关跟踪, 永不取消, 仅取消任务句柄) */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** GNSS 降级开关跟踪任务 (stop() 取消, start() 重建) */
    @Volatile
    private var fallbackJob: Job? = null

    // ===== GNSS 降级状态 (v0.5.0) =====

    /** 最近一次 BLE 成功帧的行程里程 km (降级续接基线) */
    @Volatile
    private var lastBleTripKm: Float = 0f

    /** 上一次 GNSS 帧车速 km/h (降级期间纵向加速度计算) */
    @Volatile
    private var prevGnssSpeedKmh: Float? = null

    /** 上一次 GNSS 帧时间戳 ms */
    @Volatile
    private var prevGnssTimestampMs: Long = 0L

    /**
     * 观察车辆实时数据流
     *
     * 合并 BLE + GNSS 两个数据源:
     * - BLE 帧有效 → 透传 (主数据源)
     * - BLE 帧失效 + GNSS 有效 → 运动学字段降级 + 行程续接
     *
     * @return 合并后的车辆数据 [VehicleData] Flow
     */
    fun observeVehicleData(): Flow<VehicleData> = combine(
        teslaBleProvider.observeData(),
        gnssProvider.observeData(),
    ) { ble, gnss ->
        mergeFrames(ble, gnss)
    }.onEach { prevData = it }

    /**
     * 合并 BLE 帧与 GNSS 帧 (v0.5.0)
     *
     * 合并策略:
     * 1. BLE 有效 → 直接透传 BLE 数据, 更新行程基线, 重置 GNSS 加速度追踪
     * 2. BLE 失效 + GNSS 有定位 → 保留 BLE 帧的车身状态字段(电池/温度/档位/门),
     *    运动学字段(车速/位置/航向/海拔)由 GNSS 填充,
     *    行程里程 = BLE 基线 + GNSS 增量 (不中断不重复)
     * 3. 两者都不可用 → 透传 BLE 帧 (保持原有失效保护行为)
     */
    private fun mergeFrames(ble: VehicleData, gnss: VehicleData): VehicleData {
        // 1. BLE 有效: 主数据源直通
        if (ble.isTeslaConnected && !ble.isDataStale) {
            lastBleTripKm = ble.tripDistance
            prevGnssSpeedKmh = null
            prevGnssTimestampMs = 0L
            return ble
        }

        // 2. GNSS 降级: 填充运动学字段, 行程续接
        if (gnss.isGnssActive) {
            val now = System.currentTimeMillis()
            var accelLongitudinal = 0f
            if (prevGnssSpeedKmh != null && prevGnssTimestampMs > 0L) {
                val dt = (now - prevGnssTimestampMs) / 1000f
                if (dt > 0f && dt < 30f) {
                    accelLongitudinal = (gnss.speed - prevGnssSpeedKmh!!) * 1000f / 3600f / dt
                }
            }
            prevGnssSpeedKmh = gnss.speed
            prevGnssTimestampMs = now

            return ble.copy(
                dataSource = DataSource.GNSS,
                isGnssActive = true,
                speed = gnss.speed,
                latitude = gnss.latitude,
                longitude = gnss.longitude,
                heading = gnss.heading,
                altitude = gnss.altitude,
                tripDistance = lastBleTripKm + gnss.tripDistance,
                accelLongitudinal = accelLongitudinal,
                gForce = abs(accelLongitudinal) / GRAVITY_MS2,
            )
        }

        // 3. 均不可用: 透传 BLE 帧 (失效保护逻辑保持不变)
        return ble
    }

    /**
     * 获取上一次的车辆数据(缓存值)
     *
     * @return 上一次的车辆数据, 若尚未收到任何数据则返回 null
     */
    fun getPrevData(): VehicleData? = prevData

    /**
     * 重置行程数据
     *
     * 清零行程累计里程和加速度计算的状态追踪变量。
     * 应在用户开始新行程时调用。
     */
    fun resetTrip() {
        (teslaBleProvider as? TeslaBleProvider)?.resetTrip()
        (gnssProvider as? PhoneGnssProvider)?.resetTrip()
        lastBleTripKm = 0f
        prevGnssSpeedKmh = null
        prevGnssTimestampMs = 0L
    }

    // ===== 车辆控制命令 (v0.5.0) =====

    /**
     * 发送车辆控制命令 (解锁/闭锁/前后备箱)
     *
     * 通过 VCSEC 域加密通道发送, 成功后车辆执行对应动作。
     *
     * @param command 控制命令类型
     * @return 车辆是否确认执行成功
     */
    suspend fun sendVehicleCommand(command: TeslaBleProvider.VehicleCommand): Boolean =
        (teslaBleProvider as? TeslaBleProvider)?.sendVehicleCommand(command) ?: false

    // ===== 生命周期控制 =====

    /**
     * 启动数据源
     *
     * 启动 BLE 主数据源 + GNSS 降级数据源, 并建立降级开关跟踪
     * (BLE 不可用时启用 GNSS, BLE 恢复时停用)。
     *
     * 幂等: 重复调用会先取消旧跟踪任务再重建 (stop() 后重新 start() 也能正常工作)。
     */
    suspend fun start() {
        teslaBleProvider.start()
        gnssProvider.start()
        // 取消旧跟踪任务 (防止重复启动/stop 后残留)
        fallbackJob?.cancel()
        // 持续跟踪 BLE 可用性, 驱动 GNSS 降级开关
        fallbackJob = scope.launch {
            // 初始按当前 BLE 可用性设置降级开关
            setGnssFallback(!teslaBleProvider.isAvailable.first())
            teslaBleProvider.isAvailable.collect { available ->
                setGnssFallback(!available)
            }
        }
    }

    /**
     * 设置 GNSS 降级开关
     *
     * @param fallback true=启用 GNSS 降级定位
     */
    private fun setGnssFallback(fallback: Boolean) {
        (gnssProvider as? PhoneGnssProvider)?.setEnabled(fallback)
    }

    /**
     * 停止数据源, 释放蓝牙与定位资源。
     *
     * 注意: 只取消跟踪任务 [fallbackJob], 不取消常驻 [scope],
     * 保证后续 [start] 能正常重建跟踪 (修复: 原实现取消 scope 导致
     * Activity 重建后 GNSS 降级跟踪永久失效)。
     */
    suspend fun stop() {
        fallbackJob?.cancel()
        fallbackJob = null
        gnssProvider.stop()
        teslaBleProvider.stop()
    }

    /**
     * 仅启动 BLE 数据源 (等同于 [start], 兼容旧调用方)
     */
    suspend fun startBle() {
        start()
    }

    /**
     * 仅停止 BLE 数据源 (等同于 [stop])
     */
    suspend fun stopBle() {
        stop()
    }

    companion object {
        /** 重力加速度 m/s² (降级期间 G 力计算用) */
        private const val GRAVITY_MS2 = 9.81f
    }
}
