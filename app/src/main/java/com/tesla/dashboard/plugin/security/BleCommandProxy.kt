package com.tesla.dashboard.plugin.security

import com.tesla.dashboard.data.source.ble.TeslaBleConstants
import com.tesla.dashboard.util.AppLog

/**
 * BLE 指令执行通道 (v0.6.0)
 *
 * 抽象底层 BLE 发送, 使 [BleCommandProxy] 可独立测试。
 * 生产实现: [com.tesla.dashboard.data.source.ble.TeslaBleProvider]。
 */
fun interface BleCommandExecutor {
    /**
     * 发送现代协议 Action 并等待车辆确认
     *
     * @param payload Action protobuf 编码
     * @return OP_STATUS_OK(0)=成功, 其他=车辆错误码, null=超时/连接失败
     */
    suspend fun sendExtendedCommand(payload: ByteArray): Int?
}

/**
 * 指令执行结果 (v0.6.0 BLE 指令代理)
 *
 * 统一的结果模型, 让调用方 (插件 / UI) 无需关心内部安全流程:
 * - [Success]: 已发送且车辆返回 OP_STATUS_OK(0) 或未返回错误
 * - [Rejected]: 白名单外指令 / 畸形 payload / 用户拒绝确认, 未发送
 * - [VehicleError]: 车辆返回错误状态码
 * - [Failed]: 连接失败 / 超时 / 会话异常
 */
sealed class CommandResult {
    /** 车辆确认执行成功 */
    data class Success(val status: Int?) : CommandResult()

    /** 指令被拒绝 (未发送): 白名单外 / 畸形 / 用户拒绝 */
    object Rejected : CommandResult()

    /** 车辆返回错误状态码 */
    data class VehicleError(val status: Int) : CommandResult()

    /** 执行失败 (连接失败 / 超时) */
    object Failed : CommandResult()
}

/**
 * 用户确认回调 — 高风险指令发送前询问用户
 *
 * @param spec 待确认的指令规格
 * @param requester 请求来源标识 (插件 ID / "ui")
 * @return true=用户同意, false=用户拒绝
 */
fun interface ConfirmationProvider {
    suspend fun confirm(spec: BleCommandSpec, requester: String): Boolean
}

/**
 * BLE 指令代理 (v0.6.0 安全核心)
 *
 * 所有车辆控制指令 (无论来自内置插件 / 外部插件 / UI) 都必须经过
 * 本代理, 执行统一的安全策略:
 *
 * 1. **白名单校验**: [BleCommandRegistry] 白名单外的指令直接拒绝,
 *    畸形 payload 拒绝 — 外部恶意插件无法构造未知指令
 * 2. **风险分级 + 用户确认**: [CommandRisk.HIGH]/[CommandRisk.CRITICAL]
 *    指令必须经 [ConfirmationProvider] 用户确认 (默认拒绝, 安全优先);
 *    只读指令无需确认直接放行
 * 3. **优先级调度**: 经 [CommandScheduler] 串行执行, 冲突分组互斥,
 *    用户确认过的指令以高优先级插入
 *
 * 无确认提供者时 (未连接 UI / 后台), 高风险指令默认拒绝 — 安全优先。
 *
 * @param executor 底层 BLE 执行通道
 * @param scheduler 指令调度器
 */
class BleCommandProxy(
    private val executor: BleCommandExecutor,
    private val scheduler: CommandScheduler = CommandScheduler(),
) {

    private val TAG = "BleCommandProxy"

    /** 当前确认提供者 (由 UI 层注册, 线程安全) */
    @Volatile
    private var confirmationProvider: ConfirmationProvider? = null

    /**
     * 注册用户确认回调 (UI 层在界面可见时注册)
     */
    fun setConfirmationProvider(provider: ConfirmationProvider?) {
        confirmationProvider = provider
    }

    /**
     * 执行一条 BLE 指令 (安全代理入口)
     *
     * @param payload 现代协议 Action protobuf 编码
     * @param requester 请求来源标识 (插件 ID / "ui"), 用于审计与确认对话框
     * @param priority 执行优先级 (用户确认过的指令传 [CommandPriority.HIGH])
     * @return 执行结果
     */
    suspend fun execute(
        payload: ByteArray,
        requester: String,
        priority: CommandPriority = CommandPriority.NORMAL,
    ): CommandResult {
        // 1. 白名单校验 + 指令识别
        val identification = BleCommandRegistry.identify(payload)
        val spec = when (identification) {
            is CommandIdentification.Known -> identification.spec
            is CommandIdentification.Unknown -> {
                AppLog.w(TAG, "Rejected: unknown command from $requester")
                return CommandResult.Rejected
            }
            is CommandIdentification.Malformed -> {
                AppLog.w(TAG, "Rejected: malformed payload from $requester")
                return CommandResult.Rejected
            }
        }

        // 2. 风险分级 + 用户确认
        if (BleCommandRegistry.requiresConfirmation(spec)) {
            val provider = confirmationProvider
            if (provider == null) {
                AppLog.w(TAG, "Rejected: no confirmation provider, high-risk '$spec.name' blocked (secure default)")
                return CommandResult.Rejected
            }
            val approved = provider.confirm(spec, requester)
            if (!approved) {
                AppLog.d(TAG, "Rejected: user declined '${spec.name}' from $requester")
                return CommandResult.Rejected
            }
        }

        // 3. 调度执行 (串行 + 优先级)
        var result: CommandResult = CommandResult.Failed
        scheduler.submit(priority = priority, group = spec.group) {
            val status = executor.sendExtendedCommand(payload)
            result = when (status) {
                null -> CommandResult.Failed
                TeslaBleConstants.OP_STATUS_OK -> CommandResult.Success(status)
                else -> CommandResult.VehicleError(status)
            }
        }
        AppLog.d(TAG, "Command '${spec.name}' from $requester → $result")
        return result
    }

    companion object {
        /** 请求来源: UI 内置命令页 */
        const val REQUESTER_UI = "ui"

        /** 请求来源: 插件 (ID 前缀) */
        const val REQUESTER_PLUGIN_PREFIX = "plugin:"
    }
}
