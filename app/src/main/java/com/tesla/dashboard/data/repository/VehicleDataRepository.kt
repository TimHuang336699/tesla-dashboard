package com.tesla.dashboard.data.repository

import com.tesla.dashboard.data.model.VehicleData
import com.tesla.dashboard.data.source.VehicleDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 车辆数据统一仓库 — 以 Tesla BLE 为唯一数据源
 *
 * 所有车辆数据均通过 BLE 蓝牙直连获取，不再依赖 GNSS/Sensor 本地传感器。
 *
 * ## 数据来源
 *
 * TeslaBleProvider 通过双域 BLE 通信获取全部车辆数据:
 * - **车速**: 车辆 CAN 总线实时车速 (Infotainment 域 DriveState)
 * - **加速度**: 由车速变化率计算 (纵向) + 航向变化率×车速 (横向)
 * - **海拔**: 车辆 GPS 模块提供 (Infotainment 域 DriveState.elevation)
 * - **行程里程**: 由总里程表差值累加 (Infotainment 域 CarState.odometer)
 * - **瞬时电耗**: 由 SOC 变化 + 里程差值计算 (VehicleData.computeConsumption)
 * - **位置/航向**: 车辆 GPS 模块提供 (Infotainment 域 DriveState)
 * - **电池/温度/档位**: Infotainment 域 ChargeState/ClimateState/DriveState
 *
 * ## 数据刷新频率
 * - BLE: 10s 轮询 (BLE 通信低延迟，秒级响应)
 *
 * ## 电耗计算
 * 内部维护 [prevData] 缓存，保存上一次的车辆数据。
 * 调用方可通过 [getPrevData] 获取缓存值，配合 [VehicleData.computeConsumption] 计算瞬时电耗。
 *
 * @param teslaBleProvider Tesla BLE 数据源 (唯一数据源)
 */
@Singleton
class VehicleDataRepository @Inject constructor(
    @Named("tesla") private val teslaBleProvider: VehicleDataSource,
) {

    /**
     * 上一次的车辆数据缓存，用于电耗计算(瞬时/区间)。
     * 使用 @Volatile 保证多线程可见性。
     */
    @Volatile
    private var prevData: VehicleData? = null

    /**
     * 观察车辆实时数据流
     *
     * 直接透传 TeslaBleProvider 的 BLE 数据流，同时更新 [prevData] 缓存。
     * 所有数据(车速/加速度/海拔/行程里程/电耗/电池/温度/档位)均来自车辆 BLE。
     *
     * @return BLE 车辆数据 [VehicleData] Flow
     */
    fun observeVehicleData(): Flow<VehicleData> = teslaBleProvider.observeData()
        .onEach { prevData = it }

    /**
     * 获取上一次的车辆数据(缓存值)
     *
     * 可用于电耗计算: 配合 [VehicleData.computeConsumption] 方法，
     * 传入 prevData 和电池容量即可计算瞬时电耗 kWh/100km。
     *
     * @return 上一次的车辆数据，若尚未收到任何数据则返回 null
     */
    fun getPrevData(): VehicleData? = prevData

    /**
     * 重置行程数据
     *
     * 清零行程累计里程和加速度计算的状态追踪变量。
     * 应在用户开始新行程时调用。
     */
    fun resetTrip() {
        (teslaBleProvider as? com.tesla.dashboard.data.source.ble.TeslaBleProvider)?.resetTrip()
    }

    // ===== 生命周期控制 =====

    /**
     * 启动 BLE 数据源
     *
     * BLE 是唯一数据源，应在应用启动时自动调用。
     * 内部会自动从已配对信息中加载 VIN，若未配对则 isAvailable 保持 false。
     */
    suspend fun start() {
        teslaBleProvider.start()
    }

    /**
     * 停止 BLE 数据源，释放蓝牙资源。
     */
    suspend fun stop() {
        teslaBleProvider.stop()
    }

    /**
     * 仅启动 BLE 数据源 (等同于 [start])
     *
     * 保留此方法以兼容现有调用方 (DashboardViewModel.init 中调用 startBle)。
     */
    suspend fun startBle() {
        teslaBleProvider.start()
    }

    /**
     * 仅停止 BLE 数据源 (等同于 [stop])
     */
    suspend fun stopBle() {
        teslaBleProvider.stop()
    }
}
