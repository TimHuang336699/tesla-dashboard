package com.tesla.dashboard.plugin.ble

import com.tesla.dashboard.R
import com.tesla.dashboard.data.source.ble.TeslaBleConstants
import com.tesla.dashboard.data.source.ble.TeslaBleMessages
import com.tesla.dashboard.plugin.DashboardPlugin
import com.tesla.dashboard.plugin.PluginCategory
import com.tesla.dashboard.plugin.PluginContext
import com.tesla.dashboard.plugin.security.BleCommandProxy
import com.tesla.dashboard.plugin.security.CommandPriority
import com.tesla.dashboard.plugin.security.CommandResult
import com.tesla.dashboard.util.AppLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE 拓展命令插件 (v0.5.2, v0.6.0 安全改造)
 *
 * 基于现代 carserver 协议 (teslamotors/vehicle-command) 提供车辆命令执行能力:
 * - 设置充电限值
 * - 开始 / 停止充电 (标准 / 最大续航)
 * - 空调开关 (自动模式) 与温度设置
 * - 充电口开关
 * - 车辆低功耗模式 (实验性)
 *
 * 命令通过 Infotainment 域的签名加密通道发送, 车辆以
 * `Response.actionStatus.result` 确认执行结果 (OP_STATUS_OK=0)。
 *
 * v0.6.0 安全改造: 所有命令不再直接调用
 * [com.tesla.dashboard.data.source.ble.TeslaBleProvider.sendExtendedCommand],
 * 而是经过 [BleCommandProxy] — 白名单校验 / 风险用户确认 / 优先级调度
 * 由代理统一处理。白名单外指令被拒绝, 高风险指令在 UI 确认前不会发送。
 */
@Singleton
class BleExtensionPlugin @Inject constructor() : DashboardPlugin {

    override val id: String = "ble-extension"
    override val nameRes: Int = R.string.plugin_ble_extension_name
    override val descriptionRes: Int = R.string.plugin_ble_extension_desc
    override val version: String = "1.1.0"
    override val category: PluginCategory = PluginCategory.BLE_COMMAND
    override val isExperimental: Boolean = true

    override suspend fun onRegister(context: PluginContext) {
        AppLog.d(TAG, "BleExtensionPlugin registered")
    }

    override suspend fun onUnregister(context: PluginContext) {
        AppLog.d(TAG, "BleExtensionPlugin unregistered")
    }

    /**
     * 设置充电限值
     *
     * @param percent 限值百分比 (50-100, 车型支持范围不同)
     * @return true=车辆确认成功 (OP_STATUS_OK)
     */
    suspend fun setChargeLimit(context: PluginContext, percent: Int): Boolean =
        execute(context, TeslaBleMessages.encodeChargeLimit(percent))

    /**
     * 开始充电
     *
     * @param context 插件上下文
     * @param maxRange 是否使用最大续航充电 (默认标准充电)
     */
    suspend fun startCharging(context: PluginContext, maxRange: Boolean = false): Boolean =
        execute(context, TeslaBleMessages.encodeChargeStart(maxRange))

    /** 停止充电 */
    suspend fun stopCharging(context: PluginContext): Boolean =
        execute(context, TeslaBleMessages.encodeChargeStop())

    /** 开关空调 (自动模式) */
    suspend fun setHvacAuto(context: PluginContext, on: Boolean): Boolean =
        execute(context, TeslaBleMessages.encodeHvacAuto(on))

    /** 设置空调温度 (双区) */
    suspend fun setHvacTemperature(context: PluginContext, driverCelsius: Float, passengerCelsius: Float): Boolean =
        execute(context, TeslaBleMessages.encodeHvacTemperature(driverCelsius, passengerCelsius))

    /** 开关充电口 */
    suspend fun setChargePort(context: PluginContext, open: Boolean): Boolean =
        execute(context, TeslaBleMessages.encodeChargePort(open))

    /** 设置车辆低功耗模式 (实验性, 慎用) */
    suspend fun setLowPowerMode(context: PluginContext, on: Boolean): Boolean =
        execute(context, TeslaBleMessages.encodeLowPowerMode(on))

    /**
     * 读取车辆数据快照 (v0.5.2)
     *
     * 请求现代协议 getVehicleData, 返回充电状态 / 续航 / 温度 / 车速等。
     * 车辆固件不支持现代协议时返回 null。
     * 只读指令, 无需用户确认。
     *
     * @param context 插件上下文
     * @return 数据快照, 失败 / 不支持时返回 null
     */
    suspend fun getVehicleData(context: PluginContext): TeslaBleMessages.VehicleDataSnapshot? =
        context.bleProvider.requestVehicleData()

    /**
     * 通过安全代理执行命令
     *
     * @return true=执行成功; false=拒绝 / 车辆错误 / 失败
     */
    private suspend fun execute(context: PluginContext, payload: ByteArray): Boolean {
        val result = context.commandProxy.execute(
            payload = payload,
            requester = BleCommandProxy.REQUESTER_PLUGIN_PREFIX + id,
            priority = CommandPriority.HIGH,
        )
        AppLog.d(TAG, "Command via proxy: $result")
        return result is CommandResult.Success
    }

    private companion object {
        const val TAG = "BleExtensionPlugin"
    }
}
