package com.tesla.dashboard

import com.tesla.dashboard.plugin.PluginEventBus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 插件事件总线测试 (v0.6.0 PluginContext API)
 *
 * 覆盖:
 * - 发布/订阅路由
 * - 取消订阅
 * - 历史记录
 * - 多订阅者
 *
 * 注意: 事件总线在 Dispatchers.Default 异步分发,
 * 测试统一使用 [PluginEventBus.publishAndWait] 确定性等待订阅者完成,
 * 不使用固定 delay, 避免 CI 慢机器上的时序竞争。
 */
class PluginEventBusTest {

    @Test
    fun `publish delivers to subscriber with payload`() = runBlocking {
        val bus = PluginEventBus()
        var received: String? = null
        bus.subscribe("ble:data_updated") { event ->
            received = event.payload as String
        }
        bus.publishAndWait("ble:data_updated", "hello")
        assertEquals("hello", received)
        bus.clearAll()
    }

    @Test
    fun `unsubscribed listener no longer receives events`() = runBlocking {
        val bus = PluginEventBus()
        val count = AtomicInteger(0)
        val sub = bus.subscribe("x") { count.incrementAndGet() }
        bus.publishAndWait("x")
        assertEquals(1, count.get())
        sub.cancel()
        bus.publishAndWait("x")
        assertEquals(1, count.get())
        bus.clearAll()
    }

    @Test
    fun `events routed only to matching type`() = runBlocking {
        val bus = PluginEventBus()
        val received = CopyOnWriteArrayList<String>()
        bus.subscribe("type_a") { received.add("a") }
        bus.subscribe("type_b") { received.add("b") }
        bus.publishAndWait("type_a")
        bus.publishAndWait("type_b")
        assertEquals(listOf("a", "b"), received.sorted())
        bus.clearAll()
    }

    @Test
    fun `multiple subscribers all receive event`() = runBlocking {
        val bus = PluginEventBus()
        val count = AtomicInteger(0)
        bus.subscribe("multi") { count.incrementAndGet() }
        bus.subscribe("multi") { count.incrementAndGet() }
        bus.publishAndWait("multi")
        assertEquals(2, count.get())
        bus.clearAll()
    }

    @Test
    fun `history records recent events`() {
        val bus = PluginEventBus()
        bus.publish("h1")
        bus.publish("h2")
        val history = bus.recentHistory()
        assertEquals(2, history.size)
        assertEquals("h1", history[0].type)
        assertEquals("h2", history[1].type)
        bus.clearAll()
        assertTrue(bus.recentHistory().isEmpty())
    }

    @Test
    fun `subscriber count reflects active subscriptions`() = runBlocking {
        val bus = PluginEventBus()
        val sub1 = bus.subscribe("a") { }
        val sub2 = bus.subscribe("b") { }
        assertEquals(2, bus.subscriberCount)
        sub1.cancel()
        assertEquals(1, bus.subscriberCount)
        sub2.cancel()
        bus.clearAll()
        assertEquals(0, bus.subscriberCount)
    }
}