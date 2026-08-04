package com.tesla.dashboard.data.model

/**
 * 统一车辆数据模型 — 以 Tesla BLE 蓝牙直连为唯一数据源
 *
 * 所有车辆数据均通过 BLE 加密通道从车辆获取，10s 轮询。
 * 不再依赖手机本地 GNSS/传感器获取车辆数据。
 *
 * ## 数据来源说明
 * - **车速**: 车辆 CAN 总线实时车速，比 GNSS 测速更精确
 * - **加速度**: 由车速变化率计算，或从车辆惯性传感器获取
 * - **海拔**: 车辆 GPS 模块提供
 * - **行程里程**: 由总里程表(odometer)差值计算
 * - **瞬时电耗**: 由电池 SOC 变化 + 里程表差值计算
 * - **位置**: 车辆 GPS 模块提供(经纬度/航向)
 *
 * @see com.tesla.dashboard.data.source.ble.TeslaBleProvider
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

    /** Tesla BLE 是否已连接 */
    val isTeslaConnected: Boolean = false,

    /** 上一次轮询的总里程表 km(内部用于行程里程计算) */
    val prevOdometer: Float? = null,

    /** 上一次轮询的车速 km/h(内部用于加速度计算) */
    val prevSpeed: Float? = null,

    /** 上一次轮询的时间戳 ms(内部用于加速度/电耗计算) */
    val prevTimestamp: Long = 0L,
) {
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
