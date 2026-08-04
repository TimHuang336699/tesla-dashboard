package com.tesla.dashboard.data.source

import com.tesla.dashboard.data.model.VehicleData
import kotlinx.coroutines.flow.Flow

/**
 * 车辆数据源统一接口
 *
 * 当前唯一实现:
 * - TeslaBleProvider: 通过 BLE 蓝牙直连获取全部车辆数据
 *   (车速/加速度/海拔/行程里程/瞬时电耗/电池/温度/档位/位置)
 *
 * Provider 负责所有数据字段的获取和导出计算。
 * VehicleDataRepository 直接透传 Provider 的数据流。
 */
interface VehicleDataSource {

    /**
     * 获取实时数据流
     * 返回的 VehicleData 包含该 Provider 负责的所有字段
     */
    fun observeData(): Flow<VehicleData>

    /** 数据源是否可用/已激活 */
    val isAvailable: Flow<Boolean>

    /** 启动数据源 */
    suspend fun start()

    /** 停止数据源 */
    suspend fun stop()
}
