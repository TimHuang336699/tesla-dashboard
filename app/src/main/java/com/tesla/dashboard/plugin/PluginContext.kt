package com.tesla.dashboard.plugin

/**
 * 插件上下文 (v0.5.2 插件系统)
 *
 * 由 [PluginManager] 在注册插件时提供, 封装插件可用的核心依赖,
 * 插件不应自行注入 Hilt 依赖, 统一从这里获取。
 */
class PluginContext(
    /** 车辆仓库 (多车列表 / 当前车辆 / 按 VIN 公钥固定) */
    val vehicleRepository: com.tesla.dashboard.data.local.VehicleRepository,
    /** 设置仓库 */
    val settingsRepository: com.tesla.dashboard.data.local.SettingsRepository,
    /** Tesla BLE 数据源 (配对 / 车辆控制) */
    val bleProvider: com.tesla.dashboard.data.source.ble.TeslaBleProvider,
)
