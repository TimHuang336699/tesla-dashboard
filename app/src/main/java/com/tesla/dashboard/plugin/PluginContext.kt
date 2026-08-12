package com.tesla.dashboard.plugin

import com.tesla.dashboard.plugin.security.BleCommandProxy

/**
 * 插件上下文 (v0.5.2 插件系统, v0.6.0 安全增强)
 *
 * 由 [PluginManager] 在注册插件时提供, 封装插件可用的核心依赖,
 * 插件不应自行注入 Hilt 依赖, 统一从这里获取。
 *
 * v0.6.0 新增:
 * - [commandProxy]: BLE 指令代理 — 所有车辆控制指令必须经过它,
 *   白名单校验 / 风险确认 / 优先级调度由代理统一完成
 * - [eventBus]: 插件事件总线 — 插件间解耦通信 (发布-订阅)
 */
class PluginContext(
    /** 车辆仓库 (多车列表 / 当前车辆 / 按 VIN 公钥固定) */
    val vehicleRepository: com.tesla.dashboard.data.local.VehicleRepository,
    /** 设置仓库 */
    val settingsRepository: com.tesla.dashboard.data.local.SettingsRepository,
    /** Tesla BLE 数据源 (配对 / 车辆控制) */
    val bleProvider: com.tesla.dashboard.data.source.ble.TeslaBleProvider,
    /** BLE 指令代理 (v0.6.0 安全核心) */
    val commandProxy: BleCommandProxy,
    /** 插件事件总线 (v0.6.0) */
    val eventBus: PluginEventBus,
)
