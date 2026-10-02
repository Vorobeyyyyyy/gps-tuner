package ru.gpstuner.ubx

/** Основные (major) системы M8 ограничены по количеству; SBAS и QZSS — дополнения. */
enum class Gnss(val id: Int, val title: String, val letter: String) {
    GPS(0, "GPS", "G"),
    GLONASS(6, "ГЛОНАСС", "R"),
    GALILEO(2, "Galileo", "E"),
    BEIDOU(3, "BeiDou", "C"),
    QZSS(5, "QZSS", "J"),
    SBAS(1, "SBAS", "S");

    companion object {
        fun byId(id: Int): Gnss? = entries.firstOrNull { it.id == id }
        val MAJOR = setOf(GPS, GLONASS, GALILEO, BEIDOU)
    }
}

enum class DynModel(val code: Int, val title: String) {
    AUTOMOTIVE(4, "Автомобиль"),
    PORTABLE(0, "Портативная"),
    PEDESTRIAN(3, "Пешеход"),
    STATIONARY(2, "Стационарная"),
    SEA(5, "Море"),
    AIRBORNE(6, "Воздух <1g");

    companion object {
        fun byCode(code: Int?): DynModel? = entries.firstOrNull { it.code == code }
    }
}

/** NMEA-сообщения класса F0. */
enum class NmeaMsg(val id: Int, val hint: String) {
    RMC(0x04, "координаты, скорость, курс"),
    GGA(0x00, "координаты, высота, спутники"),
    GSA(0x02, "DOP и активные спутники"),
    GSV(0x03, "спутники в зоне видимости"),
    VTG(0x05, "курс и скорость"),
    GLL(0x01, "только координаты"),
}

/** UBX-CFG-RATE. */
data class RateCfg(val measRateMs: Int, val navRate: Int, val timeRef: Int) {
    val hz: Double get() = 1000.0 / measRateMs / navRate

    fun encode(): ByteArray = Ubx.payload(6) {
        putShort(measRateMs.toShort())
        putShort(navRate.toShort())
        putShort(timeRef.toShort())
    }

    companion object {
        fun decode(m: UbxMessage) = RateCfg(m.u2(0), m.u2(2), m.u2(4))
    }
}

/** UBX-CFG-NAV5: меняем только динамическую модель (mask = 0x0001). */
object Nav5 {
    fun decodeDynModel(m: UbxMessage): Int? = if (m.size >= 36) m.u1(2) else null

    fun encodeDynModel(model: Int): ByteArray = Ubx.payload(36) {
        putShort(0x0001)
        put(model.toByte())
    }
}

data class GnssBlock(val gnssId: Int, val resTrkCh: Int, val maxTrkCh: Int, val reserved: Int, val flags: Long) {
    val enabled: Boolean get() = flags and 1L == 1L

    fun withEnabled(on: Boolean): GnssBlock {
        var f = if (on) flags or 1L else flags and 1L.inv()
        // включённой системе нужен хотя бы один сигнал (sigCfgMask), по умолчанию L1
        if (on && (f shr 16) and 0xFF == 0L) f = f or (0x01L shl 16)
        return copy(flags = f, maxTrkCh = if (on && maxTrkCh == 0) 8 else maxTrkCh)
    }
}

/** UBX-CFG-GNSS. */
data class GnssCfg(val msgVer: Int, val numTrkChHw: Int, val numTrkChUse: Int, val blocks: List<GnssBlock>) {
    fun has(g: Gnss) = blocks.any { it.gnssId == g.id }
    fun isEnabled(g: Gnss) = blocks.firstOrNull { it.gnssId == g.id }?.enabled == true
    fun majorEnabled() = Gnss.MAJOR.count { isEnabled(it) }

    fun withEnabled(g: Gnss, on: Boolean) =
        copy(blocks = blocks.map { if (it.gnssId == g.id) it.withEnabled(on) else it })

    fun encode(): ByteArray = Ubx.payload(4 + 8 * blocks.size) {
        put(msgVer.toByte())
        put(numTrkChHw.toByte())
        put(numTrkChUse.toByte())
        put(blocks.size.toByte())
        for (b in blocks) {
            put(b.gnssId.toByte())
            put(b.resTrkCh.toByte())
            put(b.maxTrkCh.toByte())
            put(b.reserved.toByte())
            putInt(b.flags.toInt())
        }
    }

    companion object {
        fun decode(m: UbxMessage): GnssCfg {
            val n = minOf(m.u1(3), (m.size - 4) / 8)
            val blocks = (0 until n).map {
                val o = 4 + 8 * it
                GnssBlock(m.u1(o), m.u1(o + 1), m.u1(o + 2), m.u1(o + 3), m.u4(o + 4))
            }
            return GnssCfg(m.u1(0), m.u1(1), m.u1(2), blocks)
        }
    }
}

/** UBX-CFG-MSG для NMEA (класс F0). */
object CfgMsg {
    fun poll(m: NmeaMsg) = byteArrayOf(0xF0.toByte(), m.id.toByte())

    /** Короткая форма — частота на том порту, через который пришла команда. */
    fun set(m: NmeaMsg, rate: Int) = byteArrayOf(0xF0.toByte(), m.id.toByte(), rate.toByte())

    fun isFor(r: UbxMessage, m: NmeaMsg) = r.size >= 3 && r.u1(0) == 0xF0 && r.u1(1) == m.id

    /** Ответ на опрос — частоты для 6 портов: I2C, UART1, UART2, USB, SPI, резерв. */
    fun rate(r: UbxMessage, port: Int): Int? = when {
        r.size >= 8 -> r.u1(2 + port)
        r.size == 3 -> r.u1(2)
        else -> null
    }
}

/** UBX-CFG-PRT для UART. Меняем только скорость, остальные поля возвращаем как прочитали. */
data class PortCfg(
    val portId: Int,
    val txReady: Int,
    val mode: Long,
    val baud: Int,
    val inProto: Int,
    val outProto: Int,
    val flags: Int,
) {
    fun encode(): ByteArray = Ubx.payload(20) {
        put(portId.toByte())
        put(0)
        putShort(txReady.toShort())
        putInt(mode.toInt())
        putInt(baud)
        putShort(inProto.toShort())
        putShort(outProto.toShort())
        putShort(flags.toShort())
    }

    companion object {
        const val UART1 = 1
        val BAUDS = listOf(9600, 38400, 57600, 115200)

        fun poll(port: Int) = byteArrayOf(port.toByte())

        fun decode(m: UbxMessage): PortCfg? =
            if (m.size < 20) null else PortCfg(m.u1(0), m.u2(2), m.u4(4), m.i4(8), m.u2(12), m.u2(14), m.u2(16))
    }
}

/** UBX-CFG-CFG. deviceMask 0x17 = BBR + Flash + EEPROM + SPI Flash. */
object CfgCfg {
    val SAVE: ByteArray = Ubx.payload(13) {
        putInt(0)
        putInt(0xFFFF)
        putInt(0)
        put(0x17)
    }
    val RESET: ByteArray = Ubx.payload(13) {
        putInt(0xFFFF)
        putInt(0)
        putInt(0xFFFF)
        put(0x17)
    }
}

/** UBX-MON-VER. */
data class ReceiverInfo(val software: String, val hardware: String, val extensions: List<String>) {
    val generation: Int?
        get() = when (hardware.uppercase()) {
            "00040005" -> 5
            "00040007" -> 6
            "00070000" -> 7
            "00080000" -> 8
            "00190000" -> 9
            "000A0000" -> 10
            else -> null
        }

    val chipName: String
        get() = when (val g = generation) {
            null -> "u-blox"
            in 8..10 -> "u-blox M$g"
            else -> "u-blox $g"
        }

    val firmware: String?
        get() = extensions.firstOrNull { it.startsWith("FWVER=") }?.substringAfter('=')
            ?: software.takeIf { it.isNotBlank() }

    val protocol: String?
        get() = extensions.firstNotNullOfOrNull { PROTVER.find(it)?.groupValues?.get(1) }

    val module: String?
        get() = extensions.firstOrNull { it.startsWith("MOD=") }?.substringAfter('=')

    /** Прошивка во flash («EXT CORE», как у NEO-M8N) или в ROM («ROM CORE»); null — не понять. */
    val runsFromFlash: Boolean?
        get() = when {
            software.startsWith("EXT CORE") -> true
            software.startsWith("ROM CORE") -> false
            else -> null
        }

    /**
     * Предел частоты навигации для M8 по данным u-blox (даташит NEO-M8, release notes FW 3.01):
     * flash — 10 Гц одна система, 5 Гц две, 3 Гц с Galileo; ROM — 18 / 10 Гц.
     * Если тип прошивки неизвестен, считаем как flash — это строже.
     */
    fun maxRateHz(gnss: GnssCfg?): Int {
        val majors = gnss?.majorEnabled() ?: 2
        val galileo = gnss?.isEnabled(Gnss.GALILEO) == true && majors >= 2
        return if (runsFromFlash == false) {
            if (majors >= 2) 10 else 18
        } else {
            when {
                galileo -> 3
                majors >= 2 -> 5
                else -> 10
            }
        }
    }

    /** Системы, которые поддерживает прошивка; null — прошивка не сообщает. */
    val supportedGnss: Set<Gnss>?
        get() = extensions
            .filter { GNSS_LIST.matches(it) }
            .flatMap { it.split(';') }
            .mapNotNull { TOKENS[it] }
            .toSet()
            .ifEmpty { null }

    companion object {
        private val PROTVER = Regex("""PROTVER[= ]\s*([\d.]+)""")
        private val GNSS_LIST = Regex("""[A-Z]+(;[A-Z]+)*""")
        private val TOKENS = mapOf(
            "GPS" to Gnss.GPS, "GLO" to Gnss.GLONASS, "GAL" to Gnss.GALILEO,
            "BDS" to Gnss.BEIDOU, "QZSS" to Gnss.QZSS, "SBAS" to Gnss.SBAS,
        )

        fun decode(m: UbxMessage): ReceiverInfo {
            val ext = (40 until m.size step 30)
                .filter { it + 30 <= m.size }
                .map { m.str(it, 30) }
                .filter { it.isNotEmpty() }
            return ReceiverInfo(m.str(0, 30), m.str(30, 10), ext)
        }
    }
}

/** UBX-NAV-PVT (84 байта у u-blox 7, 92 у M8). */
data class Pvt(
    val fixType: Int,
    val fixOk: Boolean,
    val numSv: Int,
    val hAccM: Double,
    val speedKmh: Double,
    val sAccKmh: Double,
    val pDop: Double,
) {
    companion object {
        fun decode(m: UbxMessage): Pvt? = if (m.size < 84) null else Pvt(
            fixType = m.u1(20),
            fixOk = m.u1(21) and 1 == 1,
            numSv = m.u1(23),
            hAccM = m.u4(40) / 1000.0,
            speedKmh = m.i4(60) * 0.0036,
            sAccKmh = m.u4(68) * 0.0036,
            pDop = m.u2(76) * 0.01,
        )
    }
}

/** Спутник из UBX-NAV-SAT. */
data class Sat(val gnssId: Int, val svId: Int, val cno: Int, val used: Boolean) {
    companion object {
        fun decodeAll(m: UbxMessage): List<Sat> {
            val n = minOf(m.u1(5), (m.size - 8) / 12)
            return (0 until n).map {
                val o = 8 + 12 * it
                Sat(m.u1(o), m.u1(o + 1), m.u1(o + 2), m.u4(o + 8) and 0x08L != 0L)
            }
        }
    }
}
