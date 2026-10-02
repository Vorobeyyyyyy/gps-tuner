package ru.gpstuner.transport

import ru.gpstuner.ubx.DynModel
import ru.gpstuner.ubx.Gnss
import ru.gpstuner.ubx.GnssBlock
import ru.gpstuner.ubx.GnssCfg
import ru.gpstuner.ubx.Nmea
import ru.gpstuner.ubx.NmeaMsg
import ru.gpstuner.ubx.Packet
import ru.gpstuner.ubx.RateCfg
import ru.gpstuner.ubx.StreamParser
import ru.gpstuner.ubx.Ubx
import ru.gpstuner.ubx.UbxMessage
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Эмулятор u-blox M8 (NEO-M8N, прошивка во flash) для демо-режима и тестов. Отвечает на те же
 * UBX-команды, что и настоящий приёмник, и выдаёт NMEA с заданной частотой.
 * Стартует «как у пользователя»: 10 Гц, сохранено во flash.
 *
 * [bridge] — адаптер с USB-UART мостом: у порта есть скорость (9600 по умолчанию), лишние данные
 * теряются, а при несовпадении скоростей ГУ видит мусор.
 */
class DemoTransport(private val startRateMs: Int = 100, private val bridge: Boolean = false) : GpsTransport {
    override val title = if (bridge) "Демо-приёмник с мостом" else "Демо-приёмник"
    override val subtitle = if (bridge) "эмуляция u-blox M8 за CH340" else "эмуляция u-blox M8, без адаптера"
    override val msgPort = if (bridge) 1 else 3

    @Volatile
    private var hostBaud = 9600
    override val baud: Int? get() = if (bridge) hostBaud else null

    override fun setBaud(baud: Int) {
        if (bridge) hostBaud = baud
    }

    /** Буфер передачи UART: опустошается со скоростью baud / 10 байт/с, при переполнении сообщения теряются. */
    private var txQueued = 0.0
    private var txDrainedAt = System.nanoTime()

    private data class Config(
        val measRate: Int,
        val navRate: Int,
        val dynModel: Int,
        val gnss: List<GnssBlock>,
        val nmea: Map<NmeaMsg, Int>,
        val baud: Int = 9600,
    )

    private class SimSat(val gnssId: Int, val svId: Int, val base: Int, val elev: Int, val azim: Int) {
        var cno = base
    }

    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "demo-gps") }
    @Volatile private var sink: ((ByteArray) -> Unit)? = null
    private val parser = StreamParser { if (it is Packet.Ubx) handle(it.msg) }
    private val rnd = Random(7)

    private val factory = Config(1000, 1, DynModel.PORTABLE.code, defaultBlocks(galileo = false), NmeaMsg.entries.associateWith { 1 })
    private var cfg = factory.copy(measRate = startRateMs, gnss = defaultBlocks(galileo = true))
    private var flash = cfg

    private var nextEpochMs = 0L
    private var fixAfterMs = 0L
    private var startMs = 0L
    private var lat = 55.7512
    private var lon = 37.6184
    private var trueSpeed = 60.0
    private var shownSpeed = 60.0
    private var hAcc = 2.0
    private val sats = buildSats()

    override fun start(onData: (ByteArray) -> Unit, onError: (Exception) -> Unit) {
        sink = onData
        startMs = System.currentTimeMillis()
        fixAfterMs = startMs + 1500
        exec.scheduleAtFixedRate({ runCatching { tick() } }, 0, 10, TimeUnit.MILLISECONDS)
    }

    override fun write(data: ByteArray) {
        // при разных скоростях приёмник видит мусор вместо команд
        if (bridge && hostBaud != cfg.baud) return
        val copy = data.copyOf()
        try {
            exec.execute { parser.feed(copy) }
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun close() {
        sink = null
        exec.shutdownNow()
    }

    // --- эпохи навигации ---

    private fun tick() {
        val now = System.currentTimeMillis()
        if (now < nextEpochMs) return
        val period = cfg.measRate * cfg.navRate
        nextEpochMs = if (nextEpochMs == 0L || now - nextEpochMs > 1000) now + period else nextEpochMs + period
        step(period / 1000.0, now)
        emitNmea(now)
    }

    private val hasFix get() = System.currentTimeMillis() >= fixAfterMs

    /** Частота выше предела M8 во flash (10 / 5 / 3 Гц): шум скорости и точность хуже. */
    private val overloaded: Boolean
        get() {
            val hz = 1000.0 / (cfg.measRate * cfg.navRate)
            val limit = when {
                enabled(Gnss.GALILEO.id) && majorEnabled() >= 2 -> 3
                majorEnabled() >= 2 -> 5
                else -> 10
            }
            return hz > limit + 0.01
        }

    private fun step(dt: Double, now: Long) {
        val t = (now - startMs) / 1000.0
        trueSpeed = 62 + 14 * sin(t / 18)
        val noise = when {
            overloaded -> 4.5
            1000.0 / cfg.measRate > 1.5 -> 1.2
            else -> 0.25
        }
        shownSpeed = (trueSpeed + rnd.nextGaussian() * noise).coerceAtLeast(0.0)
        hAcc = if (overloaded) 7.0 + abs(rnd.nextGaussian()) * 2 else 1.8 + abs(rnd.nextGaussian()) * 0.3
        val dist = trueSpeed / 3.6 * dt
        lon += dist / (111_320 * cos(Math.toRadians(lat)))
        for (s in sats) s.cno = (s.cno + rnd.nextInt(3) - 1).coerceIn(s.base - 4, s.base + 4)
    }

    private fun majorEnabled() = Gnss.MAJOR.count { g -> cfg.gnss.any { it.gnssId == g.id && it.enabled } }

    private fun enabled(gnssId: Int) = cfg.gnss.any { it.gnssId == gnssId && it.enabled }

    private fun visibleSats() = sats.filter { enabled(it.gnssId) && hasFix || enabled(it.gnssId) && it.base > 30 }

    private fun usedSats() = if (hasFix) visibleSats().filter { it.cno >= 28 } else emptyList()

    private fun emitNmea(now: Long) {
        val time = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC)
        val hhmmss = "%02d%02d%02d.%02d".format(Locale.US, time.hour, time.minute, time.second, time.nano / 10_000_000)
        val date = "%02d%02d%02d".format(Locale.US, time.dayOfMonth, time.monthValue, time.year % 100)
        val latS = "%02d%08.5f".format(Locale.US, lat.toInt(), (lat - lat.toInt()) * 60)
        val lonS = "%03d%08.5f".format(Locale.US, lon.toInt(), (lon - lon.toInt()) * 60)
        val fix = hasFix
        val knots = shownSpeed / 1.852
        val used = usedSats()
        val hdop = if (overloaded) 1.9 else 0.8
        val out = StringBuilder()
        fun add(m: NmeaMsg, body: () -> String) {
            if ((cfg.nmea[m] ?: 0) > 0) out.append(Nmea.build(body()))
        }
        add(NmeaMsg.RMC) {
            if (fix) "GNRMC,$hhmmss,A,$latS,N,$lonS,E,${f(knots, 3)},90.00,$date,,,A"
            else "GNRMC,$hhmmss,V,,,,,,,$date,,,N"
        }
        add(NmeaMsg.VTG) { if (fix) "GNVTG,90.00,T,,M,${f(knots, 3)},N,${f(shownSpeed, 3)},K,A" else "GNVTG,,,,,,,,,N" }
        add(NmeaMsg.GGA) {
            if (fix) "GNGGA,$hhmmss,$latS,N,$lonS,E,1,%02d,${f(hdop, 2)},152.4,M,14.5,M,,".format(Locale.US, used.size)
            else "GNGGA,$hhmmss,,,,,0,00,99.99,,,,,,"
        }
        add(NmeaMsg.GSA) {
            val ids = used.filter { it.gnssId == Gnss.GPS.id }.take(12).map { "%02d".format(Locale.US, it.svId) }
            val padded = (ids + List(12 - ids.size) { "" }).joinToString(",")
            "GNGSA,A,${if (fix) 3 else 1},$padded,${f(hdop * 1.6, 2)},${f(hdop, 2)},${f(hdop * 1.3, 2)}"
        }
        if ((cfg.nmea[NmeaMsg.GSV] ?: 0) > 0) {
            for ((talker, list) in visibleSats().groupBy { gsvTalker(it.gnssId) }) {
                val chunks = list.chunked(4)
                chunks.forEachIndexed { i, chunk ->
                    val sv = chunk.joinToString(",") { "%02d,%02d,%03d,%02d".format(Locale.US, it.svId, it.elev, it.azim, it.cno) }
                    out.append(Nmea.build("${talker}GSV,${chunks.size},${i + 1},%02d,$sv".format(Locale.US, list.size)))
                }
            }
        }
        add(NmeaMsg.GLL) { if (fix) "GNGLL,$latS,N,$lonS,E,$hhmmss,A,A" else "GNGLL,,,,,$hhmmss,V,N" }
        if (out.isEmpty()) return
        if (!bridge) {
            sink?.invoke(out.toString().toByteArray(Charsets.US_ASCII))
            return
        }
        // u-blox выбрасывает сообщения целиком, когда буфер порта переполнен
        for (line in out.split("\r\n").filter { it.isNotEmpty() }) emit((line + "\r\n").toByteArray(Charsets.US_ASCII))
    }

    /** Отправка через «UART»: с учётом скорости порта и совпадения скоростей. */
    private fun emit(bytes: ByteArray): Boolean {
        val now = System.nanoTime()
        txQueued = maxOf(0.0, txQueued - (now - txDrainedAt) / 1e9 * cfg.baud / 10.0)
        txDrainedAt = now
        if (txQueued + bytes.size > TX_BUFFER) return false
        txQueued += bytes.size
        sink?.invoke(if (hostBaud == cfg.baud) bytes else ByteArray(bytes.size) { (rnd.nextInt(256)).toByte() })
        return true
    }

    private fun gsvTalker(gnssId: Int) = when (gnssId) {
        Gnss.GLONASS.id -> "GL"
        Gnss.GALILEO.id -> "GA"
        Gnss.BEIDOU.id -> "GB"
        else -> "GP"
    }

    private fun f(v: Double, digits: Int) = "%.${digits}f".format(Locale.US, v)

    // --- UBX ---

    private fun send(cls: Int, id: Int, payload: ByteArray) {
        val frame = Ubx.frame(cls, id, payload)
        if (bridge) emit(frame) else sink?.invoke(frame)
    }

    private fun handle(m: UbxMessage) {
        when {
            m.cls == Ubx.MON && m.id == Ubx.MON_VER && m.size == 0 -> send(Ubx.MON, Ubx.MON_VER, monVer())
            m.cls == Ubx.NAV && m.id == Ubx.NAV_PVT && m.size == 0 -> send(Ubx.NAV, Ubx.NAV_PVT, navPvt())
            m.cls == Ubx.NAV && m.id == Ubx.NAV_SAT && m.size == 0 -> send(Ubx.NAV, Ubx.NAV_SAT, navSat())
            m.cls == Ubx.CFG -> send(Ubx.ACK, if (handleCfg(m)) Ubx.ACK_ACK else Ubx.ACK_NAK, byteArrayOf(Ubx.CFG.toByte(), m.id.toByte()))
        }
    }

    private fun handleCfg(m: UbxMessage): Boolean = when (m.id) {
        Ubx.CFG_RATE -> when (m.size) {
            0 -> true.also { send(Ubx.CFG, Ubx.CFG_RATE, RateCfg(cfg.measRate, cfg.navRate, 1).encode()) }
            6 -> {
                val r = RateCfg.decode(m)
                (r.measRateMs >= 25 && r.navRate in 1..127).also {
                    if (it) {
                        cfg = cfg.copy(measRate = r.measRateMs, navRate = r.navRate)
                        nextEpochMs = 0
                    }
                }
            }
            else -> false
        }
        Ubx.CFG_NAV5 -> when (m.size) {
            0 -> true.also { send(Ubx.CFG, Ubx.CFG_NAV5, nav5()) }
            36 -> if (m.u2(0) and 1 == 0) true else (DynModel.byCode(m.u1(2)) != null).also { if (it) cfg = cfg.copy(dynModel = m.u1(2)) }
            else -> false
        }
        Ubx.CFG_GNSS -> if (m.size == 0) {
            true.also { send(Ubx.CFG, Ubx.CFG_GNSS, GnssCfg(0, 32, 32, cfg.gnss).encode()) }
        } else {
            val g = GnssCfg.decode(m)
            // на M8 ГЛОНАСС и BeiDou одновременно не работают
            (!(g.isEnabled(Gnss.GLONASS) && g.isEnabled(Gnss.BEIDOU))).also {
                if (it) {
                    cfg = cfg.copy(gnss = g.blocks)
                    fixAfterMs = System.currentTimeMillis() + 5000
                }
            }
        }
        Ubx.CFG_MSG -> {
            val msg = NmeaMsg.entries.firstOrNull { m.size >= 2 && m.u1(0) == 0xF0 && m.u1(1) == it.id }
            when {
                msg == null -> false
                m.size == 2 -> true.also { send(Ubx.CFG, Ubx.CFG_MSG, msgRates(msg)) }
                m.size == 3 -> true.also { cfg = cfg.copy(nmea = cfg.nmea + (msg to m.u1(2))) }
                m.size == 8 -> true.also { cfg = cfg.copy(nmea = cfg.nmea + (msg to m.u1(2 + msgPort))) }
                else -> false
            }
        }
        Ubx.CFG_PRT -> when {
            !bridge -> false
            m.size == 1 && m.u1(0) == 1 -> true.also { send(Ubx.CFG, Ubx.CFG_PRT, portCfg(cfg.baud)) }
            m.size == 20 && m.u1(0) == 1 -> {
                val b = m.i4(8)
                (b in 4800..921600).also {
                    if (it) {
                        // как у настоящего: порт переключается до ответа, ACK уходит уже на новой скорости
                        Thread.sleep(250)
                        cfg = cfg.copy(baud = b)
                    }
                }
            }
            else -> false
        }
        Ubx.CFG_CFG -> (m.size == 12 || m.size == 13).also {
            if (it) {
                if (m.i4(0) != 0) flash = factory
                if (m.i4(4) != 0) flash = cfg
                if (m.i4(8) != 0) {
                    cfg = flash
                    nextEpochMs = 0
                    fixAfterMs = System.currentTimeMillis() + 3000
                }
            }
        }
        else -> false
    }

    private fun msgRates(msg: NmeaMsg): ByteArray {
        val rate = (cfg.nmea[msg] ?: 0).toByte()
        return byteArrayOf(0xF0.toByte(), msg.id.toByte(), rate, rate, 0, rate, 0, 0)
    }

    private fun portCfg(baud: Int) = ru.gpstuner.ubx.PortCfg(1, 0, 0x08C0, baud, 0x07, 0x03, 0).encode()

    private fun monVer(): ByteArray {
        val ext = listOf("ROM BASE 2.01 (75331)", "FWVER=SPG 3.01", "PROTVER=18.00", "MOD=NEO-M8N-0", "GPS;GLO;GAL;BDS", "SBAS;IMES;QZSS")
        val out = ByteArray(40 + 30 * ext.size)
        fun put(s: String, at: Int) = s.toByteArray(Charsets.US_ASCII).copyInto(out, at)
        put("EXT CORE 3.01 (111141)", 0)
        put("00080000", 30)
        ext.forEachIndexed { i, s -> put(s, 40 + 30 * i) }
        return out
    }

    private fun nav5(): ByteArray = Ubx.payload(36) {
        putShort(0xFFFF.toShort())
        put(cfg.dynModel.toByte())
        put(3)
        putInt(0)
        putInt(10000)
        put(5)
    }

    private fun navPvt(): ByteArray {
        val fix = hasFix
        val used = usedSats().size
        val speedMm = (shownSpeed / 3.6 * 1000).toInt()
        return Ubx.payload(92) {
            put(20, (if (fix) 3 else 0).toByte())
            put(21, (if (fix) 1 else 0).toByte())
            put(23, used.toByte())
            putInt(24, (lon * 1e7).toInt())
            putInt(28, (lat * 1e7).toInt())
            putInt(40, if (fix) (hAcc * 1000).toInt() else 999_000)
            putInt(52, speedMm)
            putInt(60, speedMm)
            putInt(68, ((if (overloaded) 1.4 else 0.15) / 3.6 * 1000).toInt())
            putShort(76, ((if (overloaded) 2.6 else 1.3) * 100).toInt().toShort())
        }
    }

    private fun navSat(): ByteArray {
        val visible = visibleSats()
        val used = usedSats().toSet()
        return Ubx.payload(8 + 12 * visible.size) {
            put(4, 1)
            put(5, visible.size.toByte())
            visible.forEachIndexed { i, s ->
                val o = 8 + 12 * i
                put(o, s.gnssId.toByte())
                put(o + 1, s.svId.toByte())
                put(o + 2, s.cno.toByte())
                put(o + 3, s.elev.toByte())
                putShort(o + 4, s.azim.toShort())
                putInt(o + 8, if (s in used) 0x0F else 0x04)
            }
        }
    }

    private fun buildSats(): List<SimSat> {
        val r = Random(3)
        fun make(gnssId: Int, ids: List<Int>) = ids.map { SimSat(gnssId, it, 22 + r.nextInt(25), 10 + r.nextInt(75), r.nextInt(360)) }
        return make(Gnss.GPS.id, listOf(2, 5, 7, 9, 13, 15, 18, 20, 24, 29, 30)) +
            make(Gnss.SBAS.id, listOf(123, 127, 136)) +
            make(Gnss.GALILEO.id, listOf(1, 4, 11, 19, 26, 33)) +
            make(Gnss.BEIDOU.id, listOf(6, 11, 14, 23, 28, 37)) +
            make(Gnss.QZSS.id, listOf(2)) +
            make(Gnss.GLONASS.id, listOf(1, 2, 8, 9, 15, 16, 17, 23))
    }

    private fun defaultBlocks(galileo: Boolean) = listOf(
        GnssBlock(Gnss.GPS.id, 8, 16, 0, 0x01010001),
        GnssBlock(Gnss.SBAS.id, 1, 3, 0, 0x01010001),
        GnssBlock(Gnss.GALILEO.id, 4, 8, 0, if (galileo) 0x01010001 else 0x01010000),
        GnssBlock(Gnss.BEIDOU.id, 8, 16, 0, 0x01010000),
        GnssBlock(4, 0, 8, 0, 0x03010000),
        GnssBlock(Gnss.QZSS.id, 0, 3, 0, 0x05010001),
        GnssBlock(Gnss.GLONASS.id, 8, 14, 0, 0x01010001),
    )

    private companion object {
        const val TX_BUFFER = 1000
    }
}
