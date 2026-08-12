package com.tesla.dashboard.plugin.security

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 指令调度器 (v0.6.0 优先级调度)
 *
 * 职责:
 * 1. **冲突指令串行**: BLE 会话是共享资源, 同一 [CommandGroup] 的指令
 *    严格串行执行, 防止并发指令互相打断 (充电指令与空调指令并发会
 *    抢占 GATT 连接, 导致会话错乱)。
 * 2. **优先级队列**: 高优先级指令插入队列头部, 在等待的低优先级指令
 *    让路先执行 (如用户确认过的指令不应被后台轮询类指令插队)。
 * 3. **弱互斥保护**: 通过锁保证入队/出队的线程安全性。
 *
 * 实现: 优先级排序通过维护多个优先级槽 (队首=高优先级) 完成,
 * 出队时按 HIGH → NORMAL → LOW 顺序取。所有指令共享同一执行链,
 * 天然串行, 满足 "冲突指令串行" 的要求。
 */
class CommandScheduler {

    /** 指令执行队列项 */
    private data class ScheduledEntry(
        val id: Long,
        val priority: CommandPriority,
        val group: CommandGroup,
        val block: suspend () -> Unit,
    )

    /** 各优先级队列 (队首优先执行) */
    private val queues = mapOf(
        CommandPriority.HIGH to ConcurrentLinkedDeque<ScheduledEntry>(),
        CommandPriority.NORMAL to ConcurrentLinkedDeque<ScheduledEntry>(),
        CommandPriority.LOW to ConcurrentLinkedDeque<ScheduledEntry>(),
    )

    /** 全局串行锁 — BLE 会话共享, 任何指令都必须互斥 */
    private val mutex = kotlinx.coroutines.sync.Mutex()

    /** 当前正在执行的指令 (用于冲突检测/日志) */
    @Volatile
    var executingGroup: CommandGroup? = null
        private set

    /** 队列长度 (高→低) */
    val pendingCount: Int
        get() = queues.values.sumOf { it.size }

    private val idCounter = AtomicLong(0)

    /**
     * 提交一条指令执行
     *
     * 按优先级插入对应队列, 然后抢占全局执行链。
     * 由于 mutex 串行化, 同一时刻只有一条指令在运行,
     * 冲突分组自动互斥。
     *
     * @param priority 执行优先级
     * @param group 冲突分组 (日志/审计)
     * @param block 指令执行体 (挂起函数)
     */
    suspend fun submit(
        priority: CommandPriority = CommandPriority.NORMAL,
        group: CommandGroup = CommandGroup.NONE,
        block: suspend () -> Unit,
    ) {
        val entry = ScheduledEntry(idCounter.incrementAndGet(), priority, group, block)
        queues[priority]!!.addLast(entry)
        // 抢占执行链 — 若已有执行者则等待; 用 tryLock 避免自竞争
        mutex.withLock {
            while (true) {
                val next = dequeue() ?: break
                executingGroup = next.group
                try {
                    next.block()
                } finally {
                    executingGroup = null
                }
            }
        }
    }

    /** 按优先级从队首取出下一条 */
    private fun dequeue(): ScheduledEntry? =
        queues[CommandPriority.HIGH]!!.pollFirst()
            ?: queues[CommandPriority.NORMAL]!!.pollFirst()
            ?: queues[CommandPriority.LOW]!!.pollFirst()

    /**
     * 清空所有等待队列 (释放资源/重置)
     */
    fun clear() {
        queues.values.forEach { it.clear() }
    }
}
