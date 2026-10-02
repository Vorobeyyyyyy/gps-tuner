package ru.gpstuner.ubx

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Протокол UBX: u-blox 8 / u-blox M8 Receiver Description Including Protocol Specification (UBX-13003221). */
object Ubx {
    const val NAV = 0x01
    const val ACK = 0x05
    const val CFG = 0x06
    const val MON = 0x0A

    const val ACK_NAK = 0x00
    const val ACK_ACK = 0x01
    const val NAV_PVT = 0x07
    const val NAV_SAT = 0x35
    const val CFG_PRT = 0x00
    const val CFG_MSG = 0x01
    const val CFG_RATE = 0x08
    const val CFG_CFG = 0x09
    const val CFG_NAV5 = 0x24
    const val CFG_GNSS = 0x3E
    const val MON_VER = 0x04

    /** Кадр: B5 62 | класс | ID | длина LE | данные | CK_A CK_B. */
    fun frame(cls: Int, id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(payload.size + 8)
        out[0] = 0xB5.toByte()
        out[1] = 0x62
        out[2] = cls.toByte()
        out[3] = id.toByte()
        out[4] = payload.size.toByte()
        out[5] = (payload.size shr 8).toByte()
        payload.copyInto(out, 6)
        val ck = checksum(out, 2, payload.size + 4)
        out[out.size - 2] = (ck shr 8).toByte()
        out[out.size - 1] = ck.toByte()
        return out
    }

    /** 8-битный Fletcher по [len] байтам начиная с [from]. Возвращает (CK_A shl 8) or CK_B. */
    fun checksum(buf: ByteArray, from: Int, len: Int): Int {
        var a = 0
        var b = 0
        for (i in from until from + len) {
            a = (a + (buf[i].toInt() and 0xFF)) and 0xFF
            b = (b + a) and 0xFF
        }
        return (a shl 8) or b
    }

    fun payload(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()
}

class UbxMessage(val cls: Int, val id: Int, val payload: ByteArray) {
    val size: Int get() = payload.size

    fun u1(o: Int): Int = payload[o].toInt() and 0xFF
    fun i1(o: Int): Int = payload[o].toInt()
    fun u2(o: Int): Int = u1(o) or (u1(o + 1) shl 8)
    fun i2(o: Int): Int = u2(o).toShort().toInt()
    fun i4(o: Int): Int = u2(o) or (u2(o + 2) shl 16)
    fun u4(o: Int): Long = i4(o).toLong() and 0xFFFFFFFFL

    /** Строка фиксированной длины, дополненная нулями. */
    fun str(o: Int, len: Int): String =
        String(payload, o, len, Charsets.US_ASCII).substringBefore('\u0000').trim()

    fun isAck(forCls: Int, forId: Int): Boolean =
        cls == Ubx.ACK && size >= 2 && u1(0) == forCls && u1(1) == forId

    override fun toString() = "UBX %02X %02X (%d байт)".format(cls, id, size)
}
