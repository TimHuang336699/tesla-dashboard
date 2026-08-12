package com.tesla.dashboard.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件事件 (v0.6.0 插件事件总线)
 *
 * 轻量级数据载体, 由事件类型 + 载荷组成。
 * 事件类型建议使用可读字符串命名空间 (如 `"ble:vehicle_data_updated"`),
 * 载荷为任意不可变对象。
 */
data class PluginEvent(
    val type: String,
    val payload: Any? = null,
)

/**
 * 插件事件订阅
 */
interface PluginEventSubscription {
    /** 取消订阅 */
    fun cancel()
}

/**
 * 插件事件总线 (v0.6.0 PluginContext API 增强)
 *
 * 提供插件间 / 插件与宿主间的解耦通信:
 * - 发布-订阅模型, 按事件类型路由
 * - 支持多订阅者并发接收, 与 [PluginManager] 生命周期解耦
 * - 单例, 与插件系统同生命周期
 *
 * 用途示例:
 * - 插件 A 读取车辆数据后广播 `ble:data_updated`, 插件 B 订阅后刷新自身状态
 * - 宿主 UI 广播 `ui:theme_changed`, 插件响应主题变化
 *
 * 线程模型: 事件在 [Dispatchers.Default] 并行分发, 订阅回调不应阻塞;
 * 需要串行处理的订阅方自行调度。
 */
@Singleton
class PluginEventBus @Inject constructor() {

    /** 事件类型 → 订阅者集合 (线程安全) */
    private val subscriptions = ConcurrentHashMap<String, MutableSet<SubscriptionEntry>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 最近事件 (调试/审计用, 保留最近 20 条) */
    private val recentEvents = ArrayDeque<PluginEvent>()
    private val historyLock = Any()

    private data class SubscriptionEntry(
        val type: String,
        val listener: suspend (PluginEvent) -> Unit,
    ) : PluginEventSubscription {
        @Volatile
        var active = true
        override fun cancel() {
            active = false
        }
    }

    /**
     * 发布事件到指定类型的所有订阅者
     *
     * @param type 事件类型
     * @param payload 载荷
     */
    fun publish(type: String, payload: Any? = null) {
        val event = PluginEvent(type, payload)
        synchronized(historyLock) {
            recentEvents.addLast(event)
            if (recentEvents.size > MAX_HISTORY) recentEvents.removeFirst()
        }
        subscriptions[type]?.forEach { entry ->
            if (entry.active) {
                scope.launch {
                    runCatching { entry.listener(event) }
                }
            }
        }
    }

    /**
     * 订阅事件
     *
     * @param type 事件类型 (支持通配符 `*` 订阅全部)
     * @param listener 事件回调 (挂起函数)
     * @return 订阅句柄 (可取消)
     */
    fun subscribe(type: String, listener: suspend (PluginEvent) -> Unit): PluginEventSubscription {
        val entry = SubscriptionEntry(type, listener)
        subscriptions.computeIfAbsent(type) { mutableSetOf() }.add(entry)
        return entry
    }

    /**
     * 最近事件历史 (调试 / 审计, 最多 [MAX_HISTORY] 条)
     */
    fun recentHistory(): List<PluginEvent> = synchronized(historyLock) {
        recentEvents.toList()
    }

    /** 订阅总数 (审计) */
    val subscriberCount: Int
        get() = subscriptions.values.sumOf { it.count { e -> e.active } }

    /** 清理全部订阅 (测试/重置用) */
    fun clearAll() {
        subscriptions.clear()
        synchronized(historyLock) { recentEvents.clear() }
    }

    private companion object {
        const val MAX_HISTORY = 20
    }
}
