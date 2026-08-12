package com.tesla.dashboard

import com.tesla.dashboard.data.source.ble.TeslaBleConstants
import com.tesla.dashboard.data.source.ble.TeslaBleMessages
import com.tesla.dashboard.data.source.ble.TeslaProtobuf
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TeslaBleMessages 编码/解析测试
 *
 * golden bytes 与 teslamotors/vehicle-command v0.4.1 的 protobuf 定义
 * 逐字节对应 (wire format 回归保护)。
 */
class TeslaBleMessagesTest {

    // ===== 现代协议命令 (v0.5.2) =====

    @Test
    fun encodeChargeLimit_goldenBytes() {
        // ChargingSetLimitAction{percent=90} →
        //   VehicleAction{field5} → Action{field2}
        // 08 5A (percent=90), 2A 02 (VA field5, len2), 12 04 (Action field2, len4)
        assertArrayEquals(
            byteArrayOf(0x12, 0x04, 0x2A, 0x02, 0x08, 0x5A),
            TeslaBleMessages.encodeChargeLimit(90),
        )
    }

    @Test
    fun encodeChargeStart_goldenBytes() {
        // ChargingStartStopAction{start(2)} → VehicleAction{field6} → Action{field2}
        assertArrayEquals(
            byteArrayOf(0x12, 0x04, 0x32, 0x02, 0x12, 0x00),
            TeslaBleMessages.encodeChargeStart(),
        )
    }

    @Test
    fun encodeChargeStop_goldenBytes() {
        // ChargingStartStopAction{stop(5)} → VehicleAction{field6} → Action{field2}
        assertArrayEquals(
            byteArrayOf(0x12, 0x04, 0x32, 0x02, 0x2A, 0x00),
            TeslaBleMessages.encodeChargeStop(),
        )
    }

    @Test
    fun encodeHvacAuto_goldenBytes() {
        // HvacAutoAction{powerOn(1)=1} → VehicleAction{field10} → Action{field2}
        assertArrayEquals(
            byteArrayOf(0x12, 0x04, 0x52, 0x02, 0x08, 0x01),
            TeslaBleMessages.encodeHvacAuto(true),
        )
    }

    @Test
    fun encodeHvacTemperature_goldenBytes() {
        // HvacTemperatureAdjustmentAction{driver(6)=20.5, passenger(7)=21.0}
        //  → VehicleAction{field14} → Action{field2}
        assertArrayEquals(
            byteArrayOf(
                0x12, 0x0C, 0x72, 0x0A,
                0x35, 0x00, 0x00, 0xA4.toByte(), 0x41, // 20.5f LE
                0x3D, 0x00, 0x00, 0xA8.toByte(), 0x41, // 21.0f LE
            ),
            TeslaBleMessages.encodeHvacTemperature(20.5f, 21.0f),
        )
    }

    @Test
    fun encodeChargePort_goldenBytes() {
        // ChargePortDoorOpen(62) → Action{field2}; ChargePortDoorClose(61)
        assertArrayEquals(
            byteArrayOf(0x12, 0x03, 0xF2.toByte(), 0x03, 0x00),
            TeslaBleMessages.encodeChargePort(true),
        )
        assertArrayEquals(
            byteArrayOf(0x12, 0x03, 0xEA.toByte(), 0x03, 0x00),
            TeslaBleMessages.encodeChargePort(false),
        )
    }

    @Test
    fun encodeLowPowerMode_goldenBytes() {
        // SetLowPowerModeAction{lowPowerMode(1)=1} → VehicleAction{field130} → Action{field2}
        assertArrayEquals(
            byteArrayOf(0x12, 0x05, 0x92.toByte(), 0x08, 0x02, 0x08, 0x01),
            TeslaBleMessages.encodeLowPowerMode(true),
        )
    }

    @Test
    fun encodeGetVehicleData_goldenBytes() {
        // VehicleAction{getVehicleData(1)} → Action{field2}
        assertArrayEquals(
            byteArrayOf(0x12, 0x02, 0x0A, 0x00),
            TeslaBleMessages.encodeGetVehicleData(),
        )
    }

    @Test
    fun parseActionStatus_ok() {
        // Response{actionStatus(1){result(1)=1}} → OP_STATUS_ERROR=1
        assertEquals(
            TeslaBleConstants.OP_STATUS_ERROR,
            TeslaBleMessages.parseActionStatus(byteArrayOf(0x0A, 0x02, 0x08, 0x01)),
        )
        // result=0 → OP_STATUS_OK
        assertEquals(
            TeslaBleConstants.OP_STATUS_OK,
            TeslaBleMessages.parseActionStatus(byteArrayOf(0x0A, 0x02, 0x08, 0x00)),
        )
    }

    @Test
    fun parseActionStatus_missing() {
        // 空 actionStatus 消息 / 无 actionStatus 字段 → null
        assertNull(TeslaBleMessages.parseActionStatus(byteArrayOf(0x0A, 0x00)))
        assertNull(TeslaBleMessages.parseActionStatus(ByteArray(0)))
    }

    @Test
    fun parseVehicleData_fullResponse() {
        // 构造完整 Response{vehicleData}: chargeState + climateState + driveState
        // golden bytes 逐字节对应 vehicle.proto 字段号
        val response = byteArrayOf(
            0x12, 0x33, // Response.vehicleData (field2, len 51)
            // VehicleData.chargeState (field3, len 19)
            0x1A, 0x13,
            //   ChargeState.chargingState (field1): ChargingState{charging(5)} (len 2)
            0x0A, 0x02, 0x2A, 0x00,
            //   batteryRange (field111, fixed32) = 300.0f
            0xFD.toByte(), 0x06, 0x00, 0x00, 0x96.toByte(), 0x43,
            //   estBatteryRange (field112, fixed32) = 310.0f
            0x85.toByte(), 0x07, 0x00, 0x00, 0x9B.toByte(), 0x43,
            //   chargerActualCurrent (field121) = 32 A
            0xC8.toByte(), 0x07, 0x20,
            // VehicleData.climateState (field4, len 10)
            0x22, 0x0A,
            //   insideTemp (field3, fixed32) = 25.0°C
            0x1D, 0x00, 0x00, 0xC8.toByte(), 0x41,
            //   outsideTemp (field4, fixed32) = 30.0°C
            0x25, 0x00, 0x00, 0xF0.toByte(), 0x41,
            // VehicleData.driveState (field5, len 16)
            0x2A, 0x10,
            //   speed (field1) = 88 km/h
            0x08, 0x58,
            //   power (field2) = 55 kW
            0x10, 0x37,
            //   shiftState (field3) = 1 (D)
            0x18, 0x01,
            //   odometer (field4, fixed32) = 1000.0 km
            0x25, 0x00, 0x00, 0x7A, 0x44,
            //   heading (field8, fixed32) = 90.0°
            0x45, 0x00, 0x00, 0xB4.toByte(), 0x42,
        )

        val snap = TeslaBleMessages.parseVehicleData(response)
        assertNotNull(snap)

        assertEquals(TeslaBleConstants.CHARGING_STATE_CHARGING, snap!!.chargingState)
        assertTrue(snap.isCharging)
        assertEquals(300.0f, snap.batteryRangeMi!!, 0.0001f)
        assertEquals(310.0f, snap.estBatteryRangeMi!!, 0.0001f)
        assertEquals(32, snap.chargerActualCurrentA)
        assertEquals(25.0f, snap.insideTempC!!, 0.0001f)
        assertEquals(30.0f, snap.outsideTempC!!, 0.0001f)
        assertEquals(88, snap.speedKmh)
        assertEquals(55, snap.powerKw)
        assertEquals(TeslaBleConstants.SHIFT_STATE_DRIVE, snap.shiftState)
        assertEquals(1000.0f, snap.odometerKm!!, 0.0001f)
        assertEquals(90.0f, snap.headingDeg!!, 0.0001f)
    }

    @Test
    fun parseVehicleData_negativePower() {
        // 负功率 (动能回收) 编码为 uint32 大值, 应正确还原为负数
        val buf = ByteArrayOutputStream()
        val ds = ByteArrayOutputStream()
        TeslaProtobuf.writeUint32(ds, TeslaBleConstants.FIELD_DS_SPEED, 50)
        TeslaProtobuf.writeUint32(ds, TeslaBleConstants.FIELD_DS_POWER, -55)
        val vd = ByteArrayOutputStream()
        TeslaProtobuf.writeMessage(vd, TeslaBleConstants.FIELD_VD_DRIVE_STATE, ds.toByteArray())
        TeslaProtobuf.writeMessage(buf, TeslaBleConstants.FIELD_RESPONSE_VEHICLE_DATA, vd.toByteArray())

        val snap = TeslaBleMessages.parseVehicleData(buf.toByteArray())
        assertEquals(50, snap!!.speedKmh)
        assertEquals(-55, snap.powerKw)
    }

    @Test
    fun parseVehicleData_isChargingStates() {
        // 充电中/启动中 → true; 其他状态 → false
        fun buildResponse(chargingFieldNumber: Int): ByteArray {
            val cs = ByteArrayOutputStream()
            val state = ByteArrayOutputStream()
            TeslaProtobuf.writeMessage(state, chargingFieldNumber, ByteArray(0))
            TeslaProtobuf.writeMessage(cs, TeslaBleConstants.FIELD_CS_CHARGING_STATE, state.toByteArray())
            val vd = ByteArrayOutputStream()
            TeslaProtobuf.writeMessage(vd, TeslaBleConstants.FIELD_VD_CHARGE_STATE, cs.toByteArray())
            val resp = ByteArrayOutputStream()
            TeslaProtobuf.writeMessage(resp, TeslaBleConstants.FIELD_RESPONSE_VEHICLE_DATA, vd.toByteArray())
            return resp.toByteArray()
        }

        assertTrue(TeslaBleMessages.parseVehicleData(buildResponse(TeslaBleConstants.CHARGING_STATE_CHARGING))!!.isCharging)
        assertTrue(TeslaBleMessages.parseVehicleData(buildResponse(TeslaBleConstants.CHARGING_STATE_STARTING))!!.isCharging)
        assertFalse(TeslaBleMessages.parseVehicleData(buildResponse(TeslaBleConstants.CHARGING_STATE_STOPPED))!!.isCharging)
        assertFalse(TeslaBleMessages.parseVehicleData(buildResponse(TeslaBleConstants.CHARGING_STATE_DISCONNECTED))!!.isCharging)
    }

    @Test
    fun parseVehicleData_missing() {
        assertNull(TeslaBleMessages.parseVehicleData(ByteArray(0)))
        // vehicleData 存在但为空 → 快照全 null
        val snap = TeslaBleMessages.parseVehicleData(byteArrayOf(0x12, 0x00))
        assertNotNull(snap)
        assertNull(snap!!.speedKmh)
        assertNull(snap.chargingState)
    }

    // ===== VCSEC 命令 =====

    @Test
    fun encodeRkeAction_goldenBytes() {
        // UnsignedMessage{rkeAction(2)=0(解锁)}
        assertArrayEquals(
            byteArrayOf(0x10, 0x00),
            TeslaBleMessages.encodeRkeAction(TeslaBleConstants.RKE_ACTION_UNLOCK),
        )
        assertArrayEquals(
            byteArrayOf(0x10, 0x01),
            TeslaBleMessages.encodeRkeAction(TeslaBleConstants.RKE_ACTION_LOCK),
        )
    }

    @Test
    fun encodeClosureMoveRequest_structure() {
        // UnsignedMessage{closureMoveRequest(4){closure(1)=2, action(2)=2}}
        val bytes = TeslaBleMessages.encodeClosureMoveRequest(
            TeslaBleConstants.CLOSURE_TRUNK,
            TeslaBleConstants.CLOSURE_ACTION_OPEN,
        )
        val unsignedFields = TeslaProtobuf.parseAllFields(bytes)
        val cm = TeslaProtobuf.getBytes(unsignedFields, TeslaBleConstants.FIELD_UM_CLOSURE_MOVE_REQUEST)
        assertNotNull(cm)
        val cmFields = TeslaProtobuf.parseAllFields(cm!!)
        assertEquals(TeslaBleConstants.CLOSURE_TRUNK, TeslaProtobuf.getUint32(cmFields, TeslaBleConstants.FIELD_CM_CLOSURE))
        assertEquals(TeslaBleConstants.CLOSURE_ACTION_OPEN, TeslaProtobuf.getUint32(cmFields, TeslaBleConstants.FIELD_CM_ACTION))
    }

    @Test
    fun parseCommandStatus() {
        // UnsignedMessage{commandStatus(3){operationStatus(1)=2(成功)}}
        assertEquals(
            TeslaBleConstants.OP_STATUS_SUCCESS,
            TeslaBleMessages.parseCommandStatus(byteArrayOf(0x1A, 0x02, 0x08, 0x02)),
        )
        assertNull(TeslaBleMessages.parseCommandStatus(byteArrayOf(0x1A, 0x00)))
        assertNull(TeslaBleMessages.parseCommandStatus(ByteArray(0)))
    }

    // ===== 传统 carserver (getVehicleState) =====

    @Test
    fun encodeGetVehicleState_goldenBytes() {
        // Action{getVehicleState(2)} 空消息 → 12 00
        assertArrayEquals(
            byteArrayOf(0x12, 0x00),
            TeslaBleMessages.encodeGetVehicleState(),
        )
    }

    @Test
    fun parseVehicleStateResponse_full() {
        val buf = ByteArrayOutputStream()
        // VehicleState{driveState(3), chargeState(6), carState(7), climateState(8)}
        val ds = ByteArrayOutputStream()
        TeslaProtobuf.writeUint32(ds, TeslaBleConstants.FIELD_DS_SPEED_LEGACY, 60) // 60 mph
        TeslaProtobuf.writeDouble(ds, TeslaBleConstants.FIELD_DS_NATIVE_LATITUDE, 31.2304)
        TeslaProtobuf.writeFloat(ds, TeslaBleConstants.FIELD_DS_NATIVE_HEADING, 123.5f)
        val vs = ByteArrayOutputStream()
        TeslaProtobuf.writeMessage(vs, TeslaBleConstants.FIELD_VS_DRIVE_STATE, ds.toByteArray())
        val cs = ByteArrayOutputStream()
        TeslaProtobuf.writeUint32(cs, TeslaBleConstants.FIELD_CS_BATTERY_LEVEL, 87)
        TeslaProtobuf.writeMessage(vs, TeslaBleConstants.FIELD_VS_CHARGE_STATE, cs.toByteArray())
        val car = ByteArrayOutputStream()
        TeslaProtobuf.writeFloat(car, TeslaBleConstants.FIELD_CAR_ODOMETER, 12345.0f)
        TeslaProtobuf.writeMessage(vs, TeslaBleConstants.FIELD_VS_CAR_STATE, car.toByteArray())
        val cl = ByteArrayOutputStream()
        TeslaProtobuf.writeFloat(cl, TeslaBleConstants.FIELD_CLS_INSIDE_TEMP, 22.0f)
        TeslaProtobuf.writeMessage(vs, TeslaBleConstants.FIELD_VS_CLIMATE_STATE, cl.toByteArray())
        TeslaProtobuf.writeMessage(buf, TeslaBleConstants.FIELD_RESPONSE_VEHICLE_STATE, vs.toByteArray())

        val data = TeslaBleMessages.parseVehicleStateResponse(buf.toByteArray())
        assertEquals(60, data.speedMph)
        assertEquals(31.2304, data.latitude!!, 1e-6)
        assertEquals(123.5f, data.heading!!, 0.0001f)
        assertEquals(87, data.batterySOC)
        assertEquals(12345.0f, data.odometer!!, 0.0001f)
        assertEquals(22.0f, data.insideTemp!!, 0.0001f)
    }

    @Test
    fun parseVehicleStateResponse_empty() {
        val data = TeslaBleMessages.parseVehicleStateResponse(ByteArray(0))
        assertNull(data.speedMph)
        assertNull(data.batterySOC)
        assertNull(data.insideTemp)
    }

    // ===== 握手 / 签名 =====

    @Test
    fun buildHandshakeMessage_structure() {
        val pubKey = ByteArray(65) { (it + 1).toByte() }
        val msg = TeslaBleMessages.buildHandshakeMessage(pubKey)
        val fields = TeslaProtobuf.parseAllFields(msg)

        // to_destination { domain = VCSEC }
        val toDest = TeslaProtobuf.getBytes(fields, TeslaBleConstants.FIELD_RM_TO_DESTINATION)!!
        val toFields = TeslaProtobuf.parseAllFields(toDest)
        assertEquals(TeslaBleConstants.DOMAIN_VEHICLE_SECURITY, TeslaProtobuf.getUint32(toFields, TeslaBleConstants.FIELD_DEST_DOMAIN))

        // from_destination { routing_address = 16 字节 }
        val fromDest = TeslaProtobuf.getBytes(fields, TeslaBleConstants.FIELD_RM_FROM_DESTINATION)!!
        val fromFields = TeslaProtobuf.parseAllFields(fromDest)
        assertEquals(16, TeslaProtobuf.getBytes(fromFields, TeslaBleConstants.FIELD_DEST_ROUTING_ADDRESS)!!.size)

        // session_info_request { public_key }
        val sir = TeslaProtobuf.getBytes(fields, TeslaBleConstants.FIELD_RM_SESSION_INFO_REQUEST)!!
        val sirFields = TeslaProtobuf.parseAllFields(sir)
        assertArrayEquals(pubKey, TeslaProtobuf.getBytes(sirFields, TeslaBleConstants.FIELD_SIR_PUBLIC_KEY))

        // uuid 16 字节
        assertEquals(16, TeslaProtobuf.getBytes(fields, TeslaBleConstants.FIELD_RM_UUID)!!.size)
    }

    @Test
    fun encodeSignatureData_roundTrip() {
        val epoch = ByteArray(16) { 0x11 }
        val nonce = ByteArray(12) { 0x22 }
        val tag = ByteArray(16) { 0x33 }
        val data = TeslaBleMessages.encodeSignatureData(epoch, nonce, 7, 0x12345678, tag)
        val fields = TeslaProtobuf.parseAllFields(data)
        assertArrayEquals(epoch, TeslaProtobuf.getBytes(fields, TeslaBleConstants.FIELD_APS_EPOCH))
        assertArrayEquals(nonce, TeslaProtobuf.getBytes(fields, TeslaBleConstants.FIELD_APS_NONCE))
        assertEquals(7, TeslaProtobuf.getUint32(fields, TeslaBleConstants.FIELD_APS_COUNTER))
        assertEquals(0x12345678, TeslaProtobuf.getFixed32(fields, TeslaBleConstants.FIELD_APS_EXPIRES_AT))
        assertArrayEquals(tag, TeslaProtobuf.getBytes(fields, TeslaBleConstants.FIELD_APS_TAG))
    }

    @Test
    fun parseSessionInfo_roundTrip() {
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeUint32(buf, TeslaBleConstants.FIELD_SI_COUNTER, 9)
        TeslaProtobuf.writeBytes(buf, TeslaBleConstants.FIELD_SI_PUBLIC_KEY, ByteArray(65) { 1 })
        TeslaProtobuf.writeBytes(buf, TeslaBleConstants.FIELD_SI_EPOCH, ByteArray(16) { 2 })
        TeslaProtobuf.writeFixed32(buf, TeslaBleConstants.FIELD_SI_CLOCK_TIME, 0x21524111)

        val info = TeslaBleMessages.parseSessionInfo(buf.toByteArray())
        assertEquals(9, info.counter)
        assertEquals(65, info.vehiclePublicKey.size)
        assertEquals(16, info.epoch.size)
        assertEquals(0x21524111, info.clockTime)
    }

    // ===== 钥匙管理 =====

    @Test
    fun encodeAddKeyRequest_structure() {
        val pubKey = ByteArray(65) { (it + 1).toByte() }
        val msg = TeslaBleMessages.encodeAddKeyRequest(pubKey, TeslaBleConstants.ROLE_DRIVER, TeslaBleConstants.KEY_FORM_FACTOR_ANDROID_DEVICE)

        val unsignedFields = TeslaProtobuf.parseAllFields(msg)
        val wo = TeslaProtobuf.getBytes(unsignedFields, TeslaBleConstants.FIELD_UM_WHITELIST_OPERATION)!!
        val woFields = TeslaProtobuf.parseAllFields(wo)

        val pc = TeslaProtobuf.getBytes(woFields, TeslaBleConstants.FIELD_WO_ADD_KEY_AND_PERMISSIONS)!!
        val pcFields = TeslaProtobuf.parseAllFields(pc)
        val key = TeslaProtobuf.getBytes(pcFields, TeslaBleConstants.FIELD_PC_KEY)!!
        val keyFields = TeslaProtobuf.parseAllFields(key)
        assertArrayEquals(pubKey, TeslaProtobuf.getBytes(keyFields, TeslaBleConstants.FIELD_PK_PUBLIC_KEY_RAW))
        assertEquals(TeslaBleConstants.ROLE_DRIVER, TeslaProtobuf.getUint32(pcFields, TeslaBleConstants.FIELD_PC_KEY_ROLE))

        val metadata = TeslaProtobuf.getBytes(woFields, TeslaBleConstants.FIELD_WO_METADATA)!!
        val metaFields = TeslaProtobuf.parseAllFields(metadata)
        assertEquals(
            TeslaBleConstants.KEY_FORM_FACTOR_ANDROID_DEVICE,
            TeslaProtobuf.getUint32(metaFields, TeslaBleConstants.FIELD_KM_KEY_FORM_FACTOR),
        )
    }

    // ===== RoutableMessage 响应解析 =====

    @Test
    fun parseRoutableMessage_extractsFields() {
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeBytes(buf, TeslaBleConstants.FIELD_RM_PROTOBUF_MESSAGE_AS_BYTES, byteArrayOf(1, 2, 3))
        val sigBuf = ByteArrayOutputStream()
        TeslaProtobuf.writeBytes(sigBuf, TeslaBleConstants.FIELD_SD_SESSION_INFO_TAG, byteArrayOf(9, 9, 9))
        TeslaProtobuf.writeMessage(buf, TeslaBleConstants.FIELD_RM_SIGNATURE_DATA, sigBuf.toByteArray())
        TeslaProtobuf.writeUint32(buf, TeslaBleConstants.FIELD_RM_SIGNED_MESSAGE_STATUS, 2)

        val resp = TeslaBleMessages.parseRoutableMessage(buf.toByteArray())
        assertArrayEquals(byteArrayOf(1, 2, 3), resp.payload)
        assertArrayEquals(byteArrayOf(9, 9, 9), resp.hmacTag)
        assertEquals(2, resp.status)
    }

    // ===== 工具 =====

    @Test
    fun vehicleLocalName_golden() {
        assertEquals(
            "S1a87a5a75f3df858C",
            TeslaBleConstants.vehicleLocalName("5YJS0000000000000"),
        )
    }

    @Test
    fun encodeDestination_structure() {
        val domain = TeslaBleMessages.encodeDestination(domain = TeslaBleConstants.DOMAIN_INFOTAINMENT)
        val fields = TeslaProtobuf.parseAllFields(domain)
        assertEquals(TeslaBleConstants.DOMAIN_INFOTAINMENT, TeslaProtobuf.getUint32(fields, TeslaBleConstants.FIELD_DEST_DOMAIN))

        val addr = ByteArray(16) { 5 }
        val withAddr = TeslaBleMessages.encodeDestination(routingAddress = addr)
        val addrFields = TeslaProtobuf.parseAllFields(withAddr)
        assertArrayEquals(addr, TeslaProtobuf.getBytes(addrFields, TeslaBleConstants.FIELD_DEST_ROUTING_ADDRESS))
    }
}
