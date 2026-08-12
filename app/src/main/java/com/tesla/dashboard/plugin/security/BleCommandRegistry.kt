package com.tesla.dashboard.plugin.security

import com.tesla.dashboard.data.source.ble.TeslaBleConstants
import com.tesla.dashboard.data.source.ble.TeslaProtobuf

/**
 * 指令风险等级 (v0.6.0 BLE 指令代理)
 *
 * 决定一条指令是否需要用户确认 / 是否允许执行:
 * - [READ_ONLY]: 只读查询, 不改变车辆状态, 无需确认
 * - [STANDARD]: 常规操作 (空调温度 / 充电口), 可配置确认
 * - [HIGH]: 高影响操作 (充电限值 / 开始·停止充电), 必须用户确认
 * - [CRITICAL]: 危险操作 (低功耗模式), 必须用户确认
 */
enum class CommandRisk(val requiresConfirmation: Boolean) {
    READ_ONLY(false),
    STANDARD(false),
    HIGH(true),
    CRITICAL(true),
}

/**
 * 指令冲突分组 (v0.6.0 优先级调度)
 *
 * 同组指令视为互斥 (如充电相关指令之间), 调度器保证同组指令串行执行,
 * 防止并发指令互相打断 BLE 会话。
 */
enum class CommandGroup {
    NONE,
    CHARGING,
    HVAC,
    CHARGE_PORT,
    POWER_MODE,
    VEHICLE_DATA,
}

/**
 * 指令执行优先级 (v0.6.0 优先级调度)
 *
 * 高优先级指令插入队列头部, 低优先级指令在等待中让路。
 * 用户确认的指令视为高优先级 (避免确认后又被其他命令插队)。
 */
enum class CommandPriority {
    LOW,
    NORMAL,
    HIGH,
}

/**
 * 现代协议指令规格 (v0.6.0 白名单)
 *
 * 描述一条可执行的 BLE 指令及其安全属性。指令通过 VehicleAction
 * 内的 oneof 字段号识别 (见 [BleCommandRegistry])。
 *
 * @param name 指令名称 (日志/确认对话框用)
 * @param actionField VehicleAction 中该动作的字段号
 * @param risk 风险等级
 * @param group 冲突分组
 */
data class BleCommandSpec(
    val name: String,
    val actionField: Int,
    val risk: CommandRisk,
    val group: CommandGroup,
)

/**
 * 指令识别结果
 */
sealed class CommandIdentification {
    /** 非 protobuf 或解析失败 (视为恶意输入, 拒绝) */
    object Malformed : CommandIdentification()

    /** 白名单内的已知指令 */
    data class Known(val spec: BleCommandSpec) : CommandIdentification()

    /** 合法 protobuf 但不在白名单内 (拒绝执行) */
    object Unknown : CommandIdentification()
}

/**
 * BLE 指令注册表与识别器 (v0.6.0)
 *
 * 维护现代协议 (teslamotors/vehicle-command) 可执行指令的白名单,
 * 并实现 "payload → 指令" 的反向识别, 供 [BleCommandProxy] 做
 * 白名单校验 / 风险分级 / 冲突分组。
 *
 * 识别原理:
 * ```
 * Action { vehicleAction (field 2) { <oneof 动作字段> <具体动作> } }
 * ```
 * 解析两层 protobuf, 提取 VehicleAction 内被设置的字段号,
 * 与白名单映射比对。字段号不在白名单 → [CommandIdentification.Unknown]。
 *
 * 安全设计:
 * - 白名单外的字段一律拒绝, 即使未来车辆固件新增指令也不会放行
 * - 解析失败 (畸形 payload) 视为 [CommandIdentification.Malformed] 拒绝
 */
object BleCommandRegistry {

    /** 白名单映射: VehicleAction 字段号 → 指令规格 */
    val WHITELIST: Map<Int, BleCommandSpec> = listOf(
        BleCommandSpec(
            name = "get_vehicle_data",
            actionField = TeslaBleConstants.FIELD_VA_GET_VEHICLE_DATA,
            risk = CommandRisk.READ_ONLY,
            group = CommandGroup.VEHICLE_DATA,
        ),
        BleCommandSpec(
            name = "charging_set_limit",
            actionField = TeslaBleConstants.FIELD_VA_CHARGING_SET_LIMIT,
            risk = CommandRisk.HIGH,
            group = CommandGroup.CHARGING,
        ),
        BleCommandSpec(
            name = "charging_start_stop",
            actionField = TeslaBleConstants.FIELD_VA_CHARGING_START_STOP,
            risk = CommandRisk.HIGH,
            group = CommandGroup.CHARGING,
        ),
        BleCommandSpec(
            name = "hvac_auto",
            actionField = TeslaBleConstants.FIELD_VA_HVAC_AUTO,
            risk = CommandRisk.STANDARD,
            group = CommandGroup.HVAC,
        ),
        BleCommandSpec(
            name = "hvac_temperature",
            actionField = TeslaBleConstants.FIELD_VA_HVAC_TEMPERATURE_ADJUSTMENT,
            risk = CommandRisk.STANDARD,
            group = CommandGroup.HVAC,
        ),
        BleCommandSpec(
            name = "charge_port_close",
            actionField = TeslaBleConstants.FIELD_VA_CHARGE_PORT_DOOR_CLOSE,
            risk = CommandRisk.STANDARD,
            group = CommandGroup.CHARGE_PORT,
        ),
        BleCommandSpec(
            name = "charge_port_open",
            actionField = TeslaBleConstants.FIELD_VA_CHARGE_PORT_DOOR_OPEN,
            risk = CommandRisk.STANDARD,
            group = CommandGroup.CHARGE_PORT,
        ),
        BleCommandSpec(
            name = "set_low_power_mode",
            actionField = TeslaBleConstants.FIELD_VA_SET_LOW_POWER_MODE,
            risk = CommandRisk.CRITICAL,
            group = CommandGroup.POWER_MODE,
        ),
    ).associateBy { it.actionField }

    /**
     * 识别现代协议 Action payload 对应的指令
     *
     * @param payload Action protobuf 编码
     * @return 识别结果 (Malformed / Known / Unknown)
     */
    fun identify(payload: ByteArray): CommandIdentification {
        val actionFields = try {
            TeslaProtobuf.parseAllFields(payload)
        } catch (e: IllegalArgumentException) {
            return CommandIdentification.Malformed
        }
        val vehicleActionBytes = TeslaProtobuf.getBytes(actionFields, TeslaBleConstants.FIELD_ACTION_VEHICLE_ACTION)
            ?: return CommandIdentification.Unknown
        val vaFields = try {
            TeslaProtobuf.parseAllFields(vehicleActionBytes)
        } catch (e: IllegalArgumentException) {
            return CommandIdentification.Malformed
        }
        // 找出 VehicleAction 中设置的动作字段 (应恰有一个)
        val actionFieldNumber = vaFields.firstOrNull { it.wireType != 0 || it.fieldNumber !in IGNORED_SCALAR_FIELDS }?.fieldNumber
            ?: return CommandIdentification.Unknown
        val spec = WHITELIST[actionFieldNumber]
        return if (spec != null) {
            CommandIdentification.Known(spec)
        } else {
            CommandIdentification.Unknown
        }
    }

    /**
     * 需要用户确认的指令规格 (供确认对话框列举)
     */
    fun requiresConfirmation(spec: BleCommandSpec): Boolean = spec.risk.requiresConfirmation

    /**
     * VehicleAction 中忽略的标量字段 (未来协议版本可能填充,
     * 不影响 oneof 动作识别)。
     */
    private val IGNORED_SCALAR_FIELDS = setOf<Int>()
}
