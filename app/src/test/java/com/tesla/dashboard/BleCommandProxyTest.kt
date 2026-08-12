package com.tesla.dashboard

import com.tesla.dashboard.data.source.ble.TeslaBleConstants
import com.tesla.dashboard.data.source.ble.TeslaBleMessages
import com.tesla.dashboard.data.source.ble.TeslaProtobuf
import com.tesla.dashboard.plugin.security.BleCommandExecutor
import com.tesla.dashboard.plugin.security.BleCommandProxy
import com.tesla.dashboard.plugin.security.CommandPriority
import com.tesla.dashboard.plugin.security.CommandResult
import com.tesla.dashboard.plugin.security.CommandScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLE 指令代理测试 (v0.6.0 安全核心)
 *
 * 覆盖:
 * - 白名单外指令拒绝 (未发送到车辆)
 * - 畸形 payload 拒绝
 * - 高风险指令无确认提供者时默认拒绝 (安全优先)
 * - 高风险指令用户拒绝时拦截
 * - 高风险指令用户确认后放行
 * - 只读指令无需确认直接放行
 * - 车辆错误码 / 失败映射
 * - 串行调度
 */
class BleCommandProxyTest {

    // ===== 测试替身: 记录发送次数与返回状态 =====

    private class FakeExecutor(var status: Int? = TeslaBleConstants.OP_STATUS_OK) : BleCommandExecutor {
        val sentPayloads = mutableListOf<ByteArray>()
        override suspend fun sendExtendedCommand(payload: ByteArray): Int? {
            sentPayloads.add(payload)
            return status
        }
    }

    // ===== 白名单强制 =====

    @Test
    fun `whitelisted read-only command passes without confirmation`() = runTest {
        val executor = FakeExecutor()
        val proxy = BleCommandProxy(executor, CommandScheduler())
        // 不注册确认提供者 — 只读指令应无需确认直接放行
        val result = proxy.execute(TeslaBleMessages.encodeGetVehicleData(), "test")
        assertTrue(result is CommandResult.Success)
        assertEquals(1, executor.sentPayloads.size)
    }

    @Test
    fun `unknown command is rejected and never sent`() = runTest {
        val executor = FakeExecutor()
        val proxy = BleCommandProxy(executor, CommandScheduler())
        val unknownAction = buildUnknownAction()
        val result = proxy.execute(unknownAction, "test")
        assertEquals(CommandResult.Rejected, result)
        assertEquals(0, executor.sentPayloads.size)
    }

    @Test
    fun `malformed payload is rejected and never sent`() = runTest {
        val executor = FakeExecutor()
        val proxy = BleCommandProxy(executor, CommandScheduler())
        val malformed = byteArrayOf(0x12.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        val result = proxy.execute(malformed, "test")
        assertEquals(CommandResult.Rejected, result)
        assertEquals(0, executor.sentPayloads.size)
    }

    // ===== 高风险指令确认 =====

    @Test
    fun `high-risk command is blocked when no confirmation provider`() = runTest {
        val executor = FakeExecutor()
        val proxy = BleCommandProxy(executor, CommandScheduler())
        // 无确认提供者 → 安全默认拒绝
        val result = proxy.execute(TeslaBleMessages.encodeChargeLimit(80), "test")
        assertEquals(CommandResult.Rejected, result)
        assertEquals(0, executor.sentPayloads.size)
    }

    @Test
    fun `high-risk command is blocked when user declines`() = runTest {
        val executor = FakeExecutor()
        val proxy = BleCommandProxy(executor, CommandScheduler())
        var asked = false
        proxy.setConfirmationProvider { spec, _ ->
            asked = true
            assertEquals("charging_set_limit", spec.name)
            false // 用户拒绝
        }
        val result = proxy.execute(TeslaBleMessages.encodeChargeLimit(80), "test")
        assertEquals(CommandResult.Rejected, result)
        assertTrue(asked)
        assertEquals(0, executor.sentPayloads.size)
    }

    @Test
    fun `high-risk command passes when user approves`() = runTest {
        val executor = FakeExecutor()
        val proxy = BleCommandProxy(executor, CommandScheduler())
        var asked = false
        proxy.setConfirmationProvider { _, _ ->
            asked = true
            true // 用户同意
        }
        val result = proxy.execute(TeslaBleMessages.encodeChargeStart(), "plugin:ble-extension")
        assertTrue(result is CommandResult.Success)
        assertTrue(asked)
        assertEquals(1, executor.sentPayloads.size)
    }

    @Test
    fun `requester id is passed to confirmation provider`() = runTest {
        val executor = FakeExecutor()
        val proxy = BleCommandProxy(executor, CommandScheduler())
        var requesterSeen: String? = null
        proxy.setConfirmationProvider { _, requester ->
            requesterSeen = requester
            true
        }
        proxy.execute(TeslaBleMessages.encodeChargeLimit(80), "plugin:ble-extension")
        assertEquals("plugin:ble-extension", requesterSeen)
    }

    @Test
    fun `confirmation provider can be unset`() = runTest {
        val executor = FakeExecutor()
        val proxy = BleCommandProxy(executor, CommandScheduler())
        proxy.setConfirmationProvider { _, _ -> true }
        proxy.setConfirmationProvider(null)
        val result = proxy.execute(TeslaBleMessages.encodeChargeStart(), "test")
        assertEquals(CommandResult.Rejected, result)
        assertEquals(0, executor.sentPayloads.size)
    }

    // ===== 结果映射 =====

    @Test
    fun `vehicle error status maps to VehicleError`() = runTest {
        val executor = FakeExecutor(status = TeslaBleConstants.OP_STATUS_ERROR)
        val proxy = BleCommandProxy(executor, CommandScheduler())
        proxy.setConfirmationProvider { _, _ -> true }
        val result = proxy.execute(TeslaBleMessages.encodeChargeLimit(80), "test")
        assertEquals(CommandResult.VehicleError(TeslaBleConstants.OP_STATUS_ERROR), result)
        assertEquals(1, executor.sentPayloads.size)
    }

    @Test
    fun `null status maps to Failed`() = runTest {
        val executor = FakeExecutor(status = null)
        val proxy = BleCommandProxy(executor, CommandScheduler())
        proxy.setConfirmationProvider { _, _ -> true }
        val result = proxy.execute(TeslaBleMessages.encodeChargeStart(), "test")
        assertEquals(CommandResult.Failed, result)
    }

    // ===== 优先级调度 =====

    @Test
    fun `scheduler serializes commands`() = runTest {
        val scheduler = CommandScheduler()
        val order = mutableListOf<String>()
        scheduler.submit(priority = CommandPriority.LOW) { order.add("low1") }
        scheduler.submit(priority = CommandPriority.HIGH) { order.add("high1") }
        scheduler.submit(priority = CommandPriority.NORMAL) { order.add("normal1") }
        // 串行执行: submit 内立即抢占执行链, 顺序 = 提交顺序
        assertEquals(listOf("low1", "high1", "normal1"), order)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `scheduler runs concurrent submissions sequentially`() = runTest {
        val scheduler = CommandScheduler()
        val execution = mutableListOf<Int>()
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        repeat(5) { index ->
            jobs.add(launch {
                scheduler.submit(priority = CommandPriority.NORMAL) {
                    execution.add(index)
                    delay(10)
                }
            })
        }
        jobs.forEach { it.join() }
        assertEquals(5, execution.size)
        assertEquals(execution.sorted(), execution) // 严格串行 (无并发交错)
    }

    // ===== 辅助 =====

    private fun buildUnknownAction(): ByteArray {
        val vaBuf = java.io.ByteArrayOutputStream()
        TeslaProtobuf.writeMessage(vaBuf, 999, ByteArray(0))
        val actionBuf = java.io.ByteArrayOutputStream()
        TeslaProtobuf.writeMessage(actionBuf, TeslaBleConstants.FIELD_ACTION_VEHICLE_ACTION, vaBuf.toByteArray())
        return actionBuf.toByteArray()
    }
}
