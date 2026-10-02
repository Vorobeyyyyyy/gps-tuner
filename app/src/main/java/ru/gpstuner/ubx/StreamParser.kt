package ru.gpstuner.ubx

sealed interface Packet {
    class Ubx(val msg: UbxMessage) : Packet
    class Nmea(val sentence: String) : Packet
}

/**
 * Делит поток байт от приёмника на UBX-кадры и NMEA-строки.
 * Кадры могут приходить кусками; мусор между ними отбрасывается.
 */
class StreamParser(private val onPacket: (Packet) -> Unit) {
    private var buf = ByteArray(8192)
    private var len = 0

    /** Кадры с неверной контрольной суммой и оборванные строки. */
    var errors = 0
        private set

    fun feed(data: ByteArray, count: Int = data.size) {
        if (len + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + count))
        data.copyInto(buf, len, 0, count)
        len += count

        var pos = 0
        while (pos < len) {
            val used = parseAt(pos)
            if (used == 0) break
            pos += used
        }
        buf.copyInto(buf, 0, pos, len)
        len -= pos
    }

    /** Сколько байт обработано с позиции [p]; 0 — сообщение пришло не целиком. */
    private fun parseAt(p: Int): Int {
        val avail = len - p
        return when (buf[p].toInt() and 0xFF) {
            0xB5 -> {
                if (avail < 2) return 0
                if (buf[p + 1] != 0x62.toByte()) return 1
                if (avail < 6) return 0
                val plen = (buf[p + 4].toInt() and 0xFF) or ((buf[p + 5].toInt() and 0xFF) shl 8)
                if (plen > MAX_UBX) {
                    errors++
                    return 1
                }
                val total = plen + 8
                if (avail < total) return 0
                val ck = Ubx.checksum(buf, p + 2, plen + 4)
                val got = ((buf[p + total - 2].toInt() and 0xFF) shl 8) or (buf[p + total - 1].toInt() and 0xFF)
                if (ck != got) {
                    errors++
                    return 1
                }
                val msg = UbxMessage(
                    buf[p + 2].toInt() and 0xFF,
                    buf[p + 3].toInt() and 0xFF,
                    buf.copyOfRange(p + 6, p + 6 + plen),
                )
                onPacket(Packet.Ubx(msg))
                total
            }
            '$'.code -> {
                var i = p + 1
                while (i < len && i - p < MAX_NMEA) {
                    val c = buf[i].toInt() and 0xFF
                    if (c == '\n'.code) {
                        val line = String(buf, p, i - p, Charsets.US_ASCII).trimEnd('\r')
                        if (Nmea.checksumOk(line)) onPacket(Packet.Nmea(line)) else errors++
                        return i - p + 1
                    }
                    if (c != '\r'.code && (c < 0x20 || c > 0x7E)) {
                        // строку оборвало другое сообщение
                        errors++
                        return i - p
                    }
                    i++
                }
                if (i - p >= MAX_NMEA) {
                    errors++
                    1
                } else {
                    0
                }
            }
            else -> 1
        }
    }

    private companion object {
        const val MAX_UBX = 4096
        const val MAX_NMEA = 120
    }
}
