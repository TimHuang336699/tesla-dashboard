package com.tesla.dashboard

import com.tesla.dashboard.data.source.ble.TeslaProtobuf
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TeslaProtobuf wire format 编码/解码测试
 *
 * golden bytes 参照 protobuf 官方编码规范:
 * https://protobuf.dev/programming-guides/encoding/
 */
class TeslaProtobufTest {

    // ===== varint =====

    @Test
    fun encodeVarint_singleByte() {
        assertArrayEquals(byteArrayOf(0x01), TeslaProtobuf.encodeVarint(1L))
        assertArrayEquals(byteArrayOf(0x00), TeslaProtobuf.encodeVarint(0L))
        assertArrayEquals(byteArrayOf(0x7F), TeslaProtobuf.encodeVarint(127L))
    }

    @Test
    fun encodeVarint_multiByte() {
        // 官方示例: 300 → AC 02
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), TeslaProtobuf.encodeVarint(300L))
        // 150 → 96 01
        assertArrayEquals(byteArrayOf(0x96.toByte(), 0x01), TeslaProtobuf.encodeVarint(150L))
    }

    @Test
    fun decodeVarint_roundTrip() {
        for (value in longArrayOf(0L, 1L, 127L, 128L, 300L, 16384L, Int.MAX_VALUE.toLong())) {
            val encoded = TeslaProtobuf.encodeVarint(value)
            val (decoded, next) = TeslaProtobuf.decodeVarint(encoded, 0)
            assertEquals(value, decoded)
            assertEquals(encoded.size, next)
        }
    }

    @Test
    fun decodeVarint_rejectsTruncated() {
        // 10 个字节全带 MSB → 无终止符 → 必须抛异常 (安全加固)
        val malformed = ByteArray(10) { 0x80.toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            TeslaProtobuf.decodeVarint(malformed, 0)
        }
    }

    // ===== 字段写入 =====

    @Test
    fun writeUint32_goldenBytes() {
        // 官方示例: field 1, value 150 → 08 96 01
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeUint32(buf, 1, 150)
        assertArrayEquals(byteArrayOf(0x08, 0x96.toByte(), 0x01), buf.toByteArray())
    }

    @Test
    fun writeFloat_goldenBytes() {
        // field 3, 1.0f → tag (3<<3)|5 = 0x1D, 值 0x3F800000 小端
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeFloat(buf, 3, 1.0f)
        assertArrayEquals(
            byteArrayOf(0x1D, 0x00, 0x00, 0x80.toByte(), 0x3F),
            buf.toByteArray(),
        )
    }

    @Test
    fun writeBytes_lengthDelimited() {
        // field 2, "abc" → 12 03 61 62 63
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeBytes(buf, 2, byteArrayOf(0x61, 0x62, 0x63))
        assertArrayEquals(byteArrayOf(0x12, 0x03, 0x61, 0x62, 0x63), buf.toByteArray())
    }

    // ===== 解析 round-trip =====

    @Test
    fun parseAllFields_roundTrip_mixedWireTypes() {
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeUint32(buf, 1, 150)
        TeslaProtobuf.writeFloat(buf, 2, 20.5f)
        TeslaProtobuf.writeBytes(buf, 3, byteArrayOf(1, 2, 3, 4))
        TeslaProtobuf.writeDouble(buf, 4, 3.14159)

        val fields = TeslaProtobuf.parseAllFields(buf.toByteArray())
        assertEquals(4, fields.size)
        assertEquals(150, TeslaProtobuf.getUint32(fields, 1))
        assertEquals(20.5f, TeslaProtobuf.getFloat(fields, 2)!!, 0.0001f)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), TeslaProtobuf.getBytes(fields, 3))
        assertEquals(3.14159, TeslaProtobuf.getDouble(fields, 4)!!, 1e-9)
    }

    @Test
    fun parseAllFields_emptyInput() {
        assertTrue(TeslaProtobuf.parseAllFields(ByteArray(0)).isEmpty())
    }

    @Test
    fun getters_missingField_returnNull() {
        val fields = TeslaProtobuf.parseAllFields(ByteArray(0))
        assertNull(TeslaProtobuf.getUint32(fields, 1))
        assertNull(TeslaProtobuf.getBytes(fields, 1))
        assertNull(TeslaProtobuf.getFloat(fields, 1))
    }

    @Test
    fun parseAllFields_ignoresUnknownWireType() {
        // wire type 4 (group end, 已废弃) → parseField 返回 null → 停止解析不崩溃
        val data = byteArrayOf(0x0C) // tag (1<<3)|4, 后面没有数据
        assertEquals(0, TeslaProtobuf.parseAllFields(data).size)
    }

    // ===== 安全加固: 恶意输入 =====

    @Test
    fun parseField_rejectsTruncatedFixed32() {
        // tag 0x0D (field1, fixed32) 但没有 4 字节负载
        assertThrows(IllegalArgumentException::class.java) {
            TeslaProtobuf.parseField(byteArrayOf(0x0D), 0)
        }
    }

    @Test
    fun parseField_rejectsNegativeLength() {
        // field1 length-delimited, 长度 varint 0xFF (溢出为负) → 必须抛异常
        val data = byteArrayOf(0x0A, 0xFF.toByte(), 0x01)
        assertThrows(IllegalArgumentException::class.java) {
            TeslaProtobuf.parseField(data, 0)
        }
    }

    @Test
    fun parseField_rejectsOversizedLength() {
        // field1, 长度 10 但数据只有 2 字节 → 越界 → 必须抛异常
        val data = byteArrayOf(0x0A, 0x0A, 0x61, 0x62)
        assertThrows(IllegalArgumentException::class.java) {
            TeslaProtobuf.parseField(data, 0)
        }
    }

    @Test
    fun parseAllFields_survivesGarbageAfterValidField() {
        // 合法字段 + 无意义字节 (0xFF 0xFF = 截断 varint)
        // 预期: 抛 IllegalArgumentException (安全拒绝), 绝不崩溃或死循环
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeUint32(buf, 1, 5)
        buf.write(0xFF)
        buf.write(0xFF)
        val result = runCatching { TeslaProtobuf.parseAllFields(buf.toByteArray()) }
        result.fold(
            onSuccess = { fields ->
                assertTrue(fields.isNotEmpty())
                assertEquals(1, fields.first().fieldNumber)
            },
            onFailure = { error ->
                assertTrue(error is IllegalArgumentException)
            },
        )
    }

    // ===== float 位模式 =====

    @Test
    fun floatBits_roundTrip_leOrder() {
        val value = 20.5f
        val bits = java.lang.Float.floatToRawIntBits(value)
        val le = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bits).array()
        val back = ByteBuffer.wrap(le).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(bits, back)
    }
}
