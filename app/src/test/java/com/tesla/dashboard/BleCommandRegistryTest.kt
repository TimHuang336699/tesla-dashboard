package com.tesla.dashboard

import com.tesla.dashboard.data.source.ble.TeslaBleConstants
import com.tesla.dashboard.data.source.ble.TeslaBleMessages
import com.tesla.dashboard.data.source.ble.TeslaProtobuf
import com.tesla.dashboard.plugin.security.BleCommandRegistry
import com.tesla.dashboard.plugin.security.CommandIdentification
import com.tesla.dashboard.plugin.security.CommandRisk
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLE 指令注册表测试 (v0.6.0)
 *
 * 覆盖:
 * - 白名单内指令正确识别
 * - 白名单外指令拒绝
 * - 畸形 payload 拒绝
 * - 风险等级映射
 */
class BleCommandRegistryTest {

    // ===== 白名单识别 =====

    @Test
    fun `known commands are identified with correct risk`() {
        // 充电限值 (HIGH, 需确认)
        val limit = BleCommandRegistry.identify(TeslaBleMessages.encodeChargeLimit(80))
        assertTrue(limit is CommandIdentification.Known)
        assertEquals(CommandRisk.HIGH, (limit as CommandIdentification.Known).spec.risk)

        // 开始充电 (HIGH)
        val start = BleCommandRegistry.identify(TeslaBleMessages.encodeChargeStart())
        assertTrue(start is CommandIdentification.Known)
        assertEquals(CommandRisk.HIGH, (start as CommandIdentification.Known).spec.risk)

        // 空调自动 (STANDARD)
        val hvac = BleCommandRegistry.identify(TeslaBleMessages.encodeHvacAuto(true))
        assertTrue(hvac is CommandIdentification.Known)
        assertEquals(CommandRisk.STANDARD, (hvac as CommandIdentification.Known).spec.risk)

        // 低功耗 (CRITICAL)
        val lowPower = BleCommandRegistry.identify(TeslaBleMessages.encodeLowPowerMode(true))
        assertTrue(lowPower is CommandIdentification.Known)
        assertEquals(CommandRisk.CRITICAL, (lowPower as CommandIdentification.Known).spec.risk)
    }

    @Test
    fun `read-only vehicle data command requires no confirmation`() {
        val result = BleCommandRegistry.identify(TeslaBleMessages.encodeGetVehicleData())
        assertTrue(result is CommandIdentification.Known)
        val spec = (result as CommandIdentification.Known).spec
        assertEquals(CommandRisk.READ_ONLY, spec.risk)
        assertFalse(BleCommandRegistry.requiresConfirmation(spec))
    }

    @Test
    fun `charge port commands are standard risk`() {
        val open = BleCommandRegistry.identify(TeslaBleMessages.encodeChargePort(true))
        assertTrue(open is CommandIdentification.Known)
        assertEquals(CommandRisk.STANDARD, (open as CommandIdentification.Known).spec.risk)

        val close = BleCommandRegistry.identify(TeslaBleMessages.encodeChargePort(false))
        assertTrue(close is CommandIdentification.Known)
        assertEquals(CommandRisk.STANDARD, (close as CommandIdentification.Known).spec.risk)
    }

    // ===== 白名单外指令 =====

    @Test
    fun `payload with unknown action field is rejected as unknown`() {
        // 构造一个 VehicleAction field 999 的 Action (白名单外)
        val unknownAction = buildAction(999)
        val result = BleCommandRegistry.identify(unknownAction)
        assertTrue(result is CommandIdentification.Unknown)
    }

    @Test
    fun `payload without vehicle action is rejected as unknown`() {
        // field 1 (varint) — 无 vehicleAction (field 2)
        val result = BleCommandRegistry.identify(byteArrayOf(0x08, 0x01))
        assertTrue(result is CommandIdentification.Unknown)
    }

    // ===== 畸形 payload =====

    @Test
    fun `malformed payload is rejected as malformed`() {
        // 截断的 length-delimited (长度超出数据范围)
        val malformed = byteArrayOf(
            0x12.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F,
        )
        val result = BleCommandRegistry.identify(malformed)
        assertTrue(result is CommandIdentification.Malformed)
    }

    @Test
    fun `empty payload is rejected as unknown`() {
        val result = BleCommandRegistry.identify(ByteArray(0))
        assertTrue(result is CommandIdentification.Unknown)
    }

    // ===== 风险等级 =====

    @Test
    fun `risk levels map to confirmation requirements`() {
        assertFalse(CommandRisk.READ_ONLY.requiresConfirmation)
        assertFalse(CommandRisk.STANDARD.requiresConfirmation)
        assertTrue(CommandRisk.HIGH.requiresConfirmation)
        assertTrue(CommandRisk.CRITICAL.requiresConfirmation)
    }

    // ===== 辅助: 构造测试用 Action =====

    /** 构造 `Action { vehicleAction { fieldN: <空消息> } }` */
    private fun buildAction(vaField: Int): ByteArray {
        val vaBuf = ByteArrayOutputStream()
        TeslaProtobuf.writeMessage(vaBuf, vaField, ByteArray(0))
        val actionBuf = ByteArrayOutputStream()
        TeslaProtobuf.writeMessage(actionBuf, TeslaBleConstants.FIELD_ACTION_VEHICLE_ACTION, vaBuf.toByteArray())
        return actionBuf.toByteArray()
    }
}
