package com.tesla.dashboard.data.model

/**
 * 车辆数据来源
 *
 * - [BLE]: Tesla 车辆蓝牙直连 (主数据源, 全部字段)
 * - [GNSS]: 手机 GNSS 定位 (BLE 断开/失败时的降级数据源, 仅运动学字段)
 */
enum class DataSource { BLE, GNSS }

/**
 * 统一车辆数据模型 — Tesla BLE 蓝牙直连为主数据源, 手机 GNSS 为降级数据源
 *
 * 车辆数据优先通过 BLE 加密通道从车辆获取 (5s 轮询, 行驶中 2.5s)。
 * 当 BLE 轮询失败/断开时 (数据失效保护触发), 由手机 GNSS 提供
 * 车速/位置/航向/海拔等运动学字段作为降级, 行程里程连续累加不中断。
 *
 * ## 数据来源说明
 * - **车速**: 车辆 CAN 总线实时车速 (BLE), 降级时手机 GNSS 测速
 * - **加速度**: 由车速变化率计算
 * - **海拔**: 车辆 GPS 模块提供 (BLE), 降级时手机 GNSS
 * - **行程里程**: BLE 时段由总里程表(odometer)差值计算, GNSS 时段由定位距离差累加
 * - **瞬时电耗**: 由电池 SOC 变化 + 里程表差值计算
 * - **位置**: 车辆 GPS 模块提供(经纬度/航向), 降级时手机 GNSS
 *
 * @see com.tesla.dashboard.data.source.ble.TeslaBleProvider
 * @see com.tesla.dashboard.data.source.gnss.PhoneGnssProvider
 */
data class VehicleData(

    // ===== BLE 实时车辆数据(全部来自 Tesla BLE) =====

    /** 车速 km/h — 来自车辆 CAN 总线 */
    val speed: Float = 0f,

    /** 纬度 — 来自车辆 GPS 模块 */
    val latitude: Double = 0.0,

    /** 经度 — 来自车辆 GPS 模块 */
    val longitude: Double = 0.0,

    /** 航向角 0-360 — 来自车辆 GPS 模块 */
    val heading: Float = 0f,

    /** 海拔 米 — 来自车辆 GPS 模块 */
    val altitude: Double = 0.0,

    /** 本次行程累计里程 km — 由里程表差值计算 */
    val tripDistance: Float = 0f,

    /** 纵向加速度 m/s² (前/后) — 由车速变化率计算 */
    val accelLongitudinal: Float = 0f,

    /** 横向加速度 m/s² (左/右) — 由航向变化率计算 */
    val accelLateral: Float = 0f,

    /** 合成 G 力 (约 1.0 = 1g 重力) */
    val gForce: Float = 0f,

    // ===== BLE 车辆状态数据 =====

    /** 电池电量百分比 0-100 */
    val batterySOC: Int? = null,

    /** 电池续航里程 km */
    val batteryRange: Float? = null,

    /** 车内温度 °C */
    val insideTemp: Float? = null,

    /** 车外温度 °C */
    val outsideTemp: Float? = null,

    /** 档位: P / R / N / D */
    val gear: String? = null,

    /** 瞬时功率 kW (v0.4.2, 正=驱动/负=动能回收) */
    val powerKw: Float? = null,

    /** 总里程表 km */
    val odometer: Float? = null,

    // ===== 门/舱/锁状态 (BLE CarState, null=未知) =====

    /** 车辆是否已锁定 */
    val isLocked: Boolean? = null,

    /** 驾驶员侧前门是否打开 */
    val df: Boolean? = null,

    /** 驾驶员侧后门是否打开 */
    val dr: Boolean? = null,

    /** 乘客侧前门是否打开 */
    val pf: Boolean? = null,

    /** 乘客侧后门是否打开 */
    val pr: Boolean? = null,

    /** 前备箱是否打开 */
    val ft: Boolean? = null,

    /** 后备箱是否打开 */
    val rt: Boolean? = null,

    // ===== 状态标志 =====

    /** 数据来源 (v0.5.0): BLE = 车辆蓝牙数据 / GNSS = 手机定位降级 */
    val dataSource: DataSource = DataSource.BLE,

    /** 手机 GNSS 是否已获得定位 (v0.5.0 GNSS 降级用) */
    val isGnssActive: Boolean = false,

    /** Tesla BLE 是否已连接 */
    val isTeslaConnected: Boolean = false,

    /**
     * 数据是否已过期 (v0.4.2 数据失效保护)
     *
     * true 表示最近一次轮询失败, 当前字段保留的是上次成功轮询的有效值,
     * UI 应继续展示该值但提示数据过期, 而不是清空归零。
     */
    val isDataStale: Boolean = false,

    /** 上一次轮询的总里程表 km(内部用于行程里程计算) */
    val prevOdometer: Float? = null,

    /** 上一次轮询的车速 km/h(内部用于加速度计算) */
    val prevSpeed: Float? = null,

    /** 上一次轮询的时间戳 ms(内部用于加速度/电耗计算) */
    val prevTimestamp: Long = 0L,
) {
    /**
     * 是否拥有可用的位置/运动学数据 (BLE 车辆 GPS 或 GNSS 降级)
     */
    val hasPosition: Boolean
        get() = isTeslaConnected || dataSource == DataSource.GNSS

    /**
     * 计算瞬时电耗 kWh/100km
     *
     * 基于 BLE 获取的电池 SOC 变化 + 里程表差值计算。
     * 全部数据来自车辆 BLE，不依赖手机本地传感器。
     *
     * 需要: 当前 SOC + 上次 SOC + 里程表差值 + 电池总容量
     * 返回 null 表示数据不足无法计算
     *
     * @param prevData 上一帧车辆数据，用于获取前一次电池 SOC 和里程表
     * @param batteryCapacityKWh 车辆电池总容量 kWh
     * @return 电耗 kWh/100km，数据不足时返回 null；无耗电时返回 0f
     */
    fun computeConsumption(
        prevData: VehicleData,
        batteryCapacityKWh: Float,
    ): Float? {
        if (!isTeslaConnected || prevData.batterySOC == null || batterySOC == null) return null
        if (odometer == null || prevData.odometer == null) return null

        val distanceDeltaKm = (odometer - prevData.odometer)
        if (distanceDeltaKm <= 0f) return null

        // 电量百分比下降 × 电池总容量 / 里程
        val socDelta = (prevData.batterySOC - batterySOC).coerceAtLeast(0)
        if (socDelta == 0) return 0f

        val energyUsedKWh = (socDelta / 100f) * batteryCapacityKWh
        return (energyUsedKWh / distanceDeltaKm) * 100f
    }

    /**
     * 计算瞬时电耗 kWh/100km(重载方法)
     *
     * 根据车型代码自动从 [BatteryConfig] 获取电池容量，无需调用方手动传入。
     *
     * @param prevData 上一帧车辆数据
     * @param modelCode 车型代码(如 "model_3_long_range")
     * @return 电耗 kWh/100km，数据不足时返回 null；无耗电时返回 0f
     */
    fun computeConsumption(
        prevData: VehicleData,
        modelCode: String?,
    ): Float? {
        val batteryCapacityKWh = BatteryConfig.getCapacityKWh(modelCode)
        return computeConsumption(prevData, batteryCapacityKWh)
    }
}
