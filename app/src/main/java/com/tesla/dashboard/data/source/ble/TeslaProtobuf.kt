package com.tesla.dashboard.data.source.ble

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 轻量级 Protobuf wire format 编解码器
 *
 * 实现 Protocol Buffers wire format 的最小子集，用于 Tesla BLE 协议消息的序列化/反序列化。
 * 避免引入完整的 protobuf-gradle-plugin 依赖，保持项目轻量。
 *
 * 支持的 wire types:
 * - 0: Varint (uint32, uint64, enum, bool)
 * - 1: Fixed64 (fixed32, float 在 protobuf 中实际用 fixed32=wire type 5)
 * - 2: Length-delimited (bytes, string, embedded messages)
 * - 5: Fixed32 (fixed32, float)
 *
 * @see <a href="https://protobuf.dev/programming-guides/encoding/">Protobuf Encoding</a>
 */
object TeslaProtobuf {

    // ===== Wire Types =====

    const val WIRE_TYPE_VARINT = 0
    const val WIRE_TYPE_FIXED64 = 1
    const val WIRE_TYPE_LENGTH_DELIMITED = 2
    const val WIRE_TYPE_FIXED32 = 5

    // ===== 编码方法 =====

    /**
     * 编码 field tag
     *
     * tag = (field_number << 3) | wire_type
     */
    private fun encodeTag(fieldNumber: Int, wireType: Int): Int =
        (fieldNumber shl 3) or wireType

    /**
     * 编码 varint
     *
     * 每 7 位一组，MSB=1 表示后续还有字节
     */
    fun encodeVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (v ushr 7 != 0L) {
            out.write((v and 0x7F or 0x80L).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
        return out.toByteArray()
    }

    /**
     * 写入 varint 字段
     */
    fun writeVarint(buf: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
        buf.write(encodeVarint(encodeTag(fieldNumber, WIRE_TYPE_VARINT).toLong()))
        buf.write(encodeVarint(value))
    }

    /**
     * 写入 uint32 字段 (varint 编码)
     */
    fun writeUint32(buf: ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        writeVarint(buf, fieldNumber, value.toLong() and 0xFFFFFFFFL)
    }

    /**
     * 写入 fixed32 字段 (4 字节小端)
     */
    fun writeFixed32(buf: ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        buf.write(encodeVarint(encodeTag(fieldNumber, WIRE_TYPE_FIXED32).toLong()))
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        buf.write(bytes)
    }

    /**
     * 写入 bytes 字段
     */
    fun writeBytes(buf: ByteArrayOutputStream, fieldNumber: Int, value: ByteArray) {
        buf.write(encodeVarint(encodeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED).toLong()))
        buf.write(encodeVarint(value.size.toLong()))
        buf.write(value)
    }

    /**
     * 写入 embedded message 字段
     * (与 writeBytes 相同，protobuf 中 embedded message 使用 length-delimited 编码)
     */
    fun writeMessage(buf: ByteArrayOutputStream, fieldNumber: Int, messageBytes: ByteArray) {
        writeBytes(buf, fieldNumber, messageBytes)
    }

    // ===== 解码方法 =====

    /**
     * 解码 varint
     *
     * @param data 字节数组
     * @param offset 起始偏移
     * @return Pair(value, nextOffset)
     */
    fun decodeVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = offset
        while (pos < data.size) {
            val byte = data[pos].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            pos++
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return Pair(result, pos)
    }

    /**
     * 解码 fixed32 (4 字节小端)
     */
    fun decodeFixed32(data: ByteArray, offset: Int): Pair<Int, Int> {
        val value = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return Pair(value, offset + 4)
    }

    /**
     * Protobuf 字段解析结果
     */
    data class ProtoField(
        val fieldNumber: Int,
        val wireType: Int,
        val value: Long,          // varint 值 (wire type 0/1/5)
        val bytesValue: ByteArray, // length-delimited 值 (wire type 2)
        val offset: Int,           // 字段值起始偏移
        val endOffset: Int,        // 字段结束偏移 (下一个字段起始)
    )

    /**
     * 解析下一个字段
     *
     * @param data 字节数组
     * @param offset 起始偏移
     * @return Pair(field, nextOffset) 或 null 表示结束
     */
    fun parseField(data: ByteArray, offset: Int): Pair<ProtoField, Int>? {
        if (offset >= data.size) return null

        // 解析 tag
        val (tag, pos) = decodeVarint(data, offset)
        val fieldNumber = (tag ushr 3).toInt()
        val wireType = (tag and 0x7).toInt()

        return when (wireType) {
            WIRE_TYPE_VARINT -> {
                val (value, endPos) = decodeVarint(data, pos)
                Pair(ProtoField(fieldNumber, wireType, value, ByteArray(0), pos, endPos), endPos)
            }
            WIRE_TYPE_FIXED32 -> {
                val (value, endPos) = decodeFixed32(data, pos)
                Pair(ProtoField(fieldNumber, wireType, value.toLong() and 0xFFFFFFFFL, ByteArray(0), pos, endPos), endPos)
            }
            WIRE_TYPE_FIXED64 -> {
                val value = ByteBuffer.wrap(data, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long
                Pair(ProtoField(fieldNumber, wireType, value, ByteArray(0), pos, pos + 8), pos + 8)
            }
            WIRE_TYPE_LENGTH_DELIMITED -> {
                val (length, lenStart) = decodeVarint(data, pos)
                val bytesStart = lenStart
                val bytesEnd = bytesStart + length.toInt()
                Pair(ProtoField(fieldNumber, wireType, 0, data.copyOfRange(bytesStart, bytesEnd), bytesStart, bytesEnd), bytesEnd)
            }
            else -> null // 不支持的 wire type
        }
    }

    /**
     * 解析所有字段
     *
     * @param data protobuf 编码的字节数组
     * @return 字段列表
     */
    fun parseAllFields(data: ByteArray): List<ProtoField> {
        val fields = mutableListOf<ProtoField>()
        var offset = 0
        while (offset < data.size) {
            val result = parseField(data, offset) ?: break
            fields.add(result.first)
            offset = result.second
        }
        return fields
    }

    /**
     * 从字段列表中获取指定字段号的 varint 值
     */
    fun getVarint(fields: List<ProtoField>, fieldNumber: Int): Long? =
        fields.find { it.fieldNumber == fieldNumber && it.wireType == WIRE_TYPE_VARINT }?.value

    /**
     * 从字段列表中获取指定字段号的 uint32 值
     */
    fun getUint32(fields: List<ProtoField>, fieldNumber: Int): Int? =
        getVarint(fields, fieldNumber)?.toInt()

    /**
     * 从字段列表中获取指定字段号的 fixed32 值
     */
    fun getFixed32(fields: List<ProtoField>, fieldNumber: Int): Int? =
        fields.find { it.fieldNumber == fieldNumber && it.wireType == WIRE_TYPE_FIXED32 }?.value?.toInt()

    /**
     * 从字段列表中获取指定字段号的 bytes 值
     */
    fun getBytes(fields: List<ProtoField>, fieldNumber: Int): ByteArray? =
        fields.find { it.fieldNumber == fieldNumber && it.wireType == WIRE_TYPE_LENGTH_DELIMITED }?.bytesValue

    /**
     * 从字段列表中获取指定字段号的 string 值 (UTF-8)
     */
    fun getString(fields: List<ProtoField>, fieldNumber: Int): String? =
        getBytes(fields, fieldNumber)?.let { String(it, Charsets.UTF_8) }

    /**
     * 从字段列表中获取指定字段号的 float 值 (wire type 5, IEEE 754)
     */
    fun getFloat(fields: List<ProtoField>, fieldNumber: Int): Float? {
        val field = fields.find { it.fieldNumber == fieldNumber && it.wireType == WIRE_TYPE_FIXED32 }
            ?: return null
        return java.lang.Float.intBitsToFloat(field.value.toInt())
    }

    /**
     * 从字段列表中获取指定字段号的 double 值 (wire type 1, IEEE 754)
     */
    fun getDouble(fields: List<ProtoField>, fieldNumber: Int): Double? {
        val field = fields.find { it.fieldNumber == fieldNumber && it.wireType == WIRE_TYPE_FIXED64 }
            ?: return null
        return java.lang.Double.longBitsToDouble(field.value)
    }

    /**
     * 写入 float 字段 (4 字节 IEEE 754 小端, wire type 5)
     */
    fun writeFloat(buf: ByteArrayOutputStream, fieldNumber: Int, value: Float) {
        buf.write(encodeVarint(encodeTag(fieldNumber, WIRE_TYPE_FIXED32).toLong()))
        val bits = java.lang.Float.floatToRawIntBits(value)
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bits).array()
        buf.write(bytes)
    }

    /**
     * 写入 double 字段 (8 字节 IEEE 754 小端, wire type 1)
     */
    fun writeDouble(buf: ByteArrayOutputStream, fieldNumber: Int, value: Double) {
        buf.write(encodeVarint(encodeTag(fieldNumber, WIRE_TYPE_FIXED64).toLong()))
        val bits = java.lang.Double.doubleToRawLongBits(value)
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bits).array()
        buf.write(bytes)
    }
}
