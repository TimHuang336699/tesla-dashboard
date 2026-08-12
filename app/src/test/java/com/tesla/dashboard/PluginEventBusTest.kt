package com.tesla.dashboard

import com.tesla.dashboard.plugin.PluginEventBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件事件总线测试 (v0.6.0 PluginContext API)
 *
 * 覆盖:
 * - 发布/订阅路由
 * - 取消订阅
 * - 历史记录
 * - 多订阅者
 *
 * 注意: 事件总线使用真实 Dispatchers.Default 分发,
 * 测试必须用 runBlocking + 真实 delay 等待异步分发。
 */
class PluginEventBusTest {

    @Test
    fun `publish delivers to subscriber with payload`() = runBlocking {
        val bus = PluginEventBus()
        var received: String? = null
        bus.subscribe("ble:data_updated") { event ->
            received = event.payload as String
        }
        bus.publish("ble:data_updated", "hello")
        delay(100)
        assertEquals("hello", received)
        bus.clearAll()
    }

    @Test
    fun `unsubscribed listener no longer receives events`() = runBlocking {
        val bus = PluginEventBus()
        var count = 0
        val sub = bus.subscribe("x") { count++ }
        bus.publish("x")
        delay(100)
        assertEquals(1, count)
        sub.cancel()
        bus.publish("x")
        delay(100)
        assertEquals(1, count)
        bus.clearAll()
    }

    @Test
    fun `events routed only to matching type`() = runBlocking {
        val bus = PluginEventBus()
        val received = mutableListOf<String>()
        bus.subscribe("type_a") { received.add("a") }
        bus.subscribe("type_b") { received.add("b") }
        bus.publish("type_a")
        bus.publish("type_b")
        delay(100)
        assertEquals(listOf("a", "b"), received)
        bus.clearAll()
    }

    @Test
    fun `multiple subscribers all receive event`() = runBlocking {
        val bus = PluginEventBus()
        var count = 0
        bus.subscribe("multi") { count++ }
        bus.subscribe("multi") { count++ }
        bus.publish("multi")
        delay(100)
        assertEquals(2, count)
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