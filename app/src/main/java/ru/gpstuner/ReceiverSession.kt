package ru.gpstuner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.gpstuner.transport.DemoTransport
import ru.gpstuner.transport.GpsTransport
import ru.gpstuner.ubx.Ack
import ru.gpstuner.ubx.CfgCfg
import ru.gpstuner.ubx.CfgMsg
import ru.gpstuner.ubx.DynModel
import ru.gpstuner.ubx.Gga
import ru.gpstuner.ubx.Gnss
import ru.gpstuner.ubx.GnssCfg
import ru.gpstuner.ubx.Nav5
import ru.gpstuner.ubx.Nmea
import ru.gpstuner.ubx.NmeaMsg
import ru.gpstuner.ubx.Packet
import ru.gpstuner.ubx.PortCfg
import ru.gpstuner.ubx.Pvt
import ru.gpstuner.ubx.RateCfg
import ru.gpstuner.ubx.ReceiverInfo
import ru.gpstuner.ubx.Rmc
import ru.gpstuner.ubx.Sat
import ru.gpstuner.ubx.Ubx
import ru.gpstuner.ubx.UbxClient
import ru.gpstuner.ubx.parseGga
import ru.gpstuner.ubx.parseRmc

/** Настройки приёмника; null — приёмник не ответил на этот опрос. */
data class ReceiverConfig(
    val rate: RateCfg?,
    val dynModel: Int?,
    val gnss: GnssCfg?,
    val nmea: Map<NmeaMsg, Int>,
    /** UART за мостом; у родного USB скорости нет. */
    val port: PortCfg? = null,
)

data class LiveStatus(
    val nmeaRate: Double? = null,
    val noData: Boolean = false,
    val fixType: Int? = null,
    val fixOk: Boolean = false,
    val numSvUsed: Int? = null,
    val hAccM: Double? = null,
    val speedKmh: Double? = null,
    val sAccKmh: Double? = null,
    val hdop: Double? = null,
    val sats: List<Sat>? = null,
    val errors: Int = 0,
    /** Доля пропускной способности UART, занятая NMEA, — то, что получит USB GPS (только для мостов). */
    val portLoad: Double? = null,
)

data class SessionState(
    val title: String,
    val subtitle: String,
    val demo: Boolean,
    val bridge: Boolean = false,
    /** Скорость, на которой адаптер работал при подключении, — её же ждёт USB GPS. */
    val initialBaud: Int? = null,
    val info: ReceiverInfo? = null,
    val loading: Boolean = true,
    val unsupported: String? = null,
    val current: ReceiverConfig? = null,
    val draft: ReceiverConfig? = null,
    val unsaved: Boolean = false,
    val busy: String? = null,
    val live: LiveStatus = LiveStatus(),
    /** Порт за мостом перегружен при подключении: сначала предлагаем поднять скорость, потом читаем настройки. */
    val overloadGate: Boolean = false,
) {
    val dirty: Boolean get() = draft != null && draft != current

    /** Сколько изменений уйдёт в приёмник по «Применить»: по одному на параметр, система и сообщение NMEA — каждое. */
    val pendingChanges: Int
        get() {
            val d = draft ?: return 0
            val c = current ?: return 0
            var n = 0
            if (d.rate != c.rate) n++
            if (d.dynModel != c.dynModel) n++
            if (d.port != c.port) n++
            val dg = d.gnss
            val cg = c.gnss
            if (dg != null && cg != null) n += Gnss.entries.count { dg.isEnabled(it) != cg.isEnabled(it) }
            n += NmeaMsg.entries.count { ((d.nmea[it] ?: 0) > 0) != ((c.nmea[it] ?: 0) > 0) }
            return n
        }

    /** Скорость порта изменена — в USB GPS надо выставить такую же. */
    val baudChanged: Int?
        get() = current?.port?.baud?.takeIf { initialBaud != null && it != initialBaud }
    val editable: Boolean get() = !loading && unsupported == null && busy == null && current != null

    fun supports(g: Gnss): Boolean {
        val gnss = draft?.gnss ?: return false
        return gnss.has(g) && (info?.supportedGnss?.contains(g) ?: true)
    }
}

/**
 * Пресеты. Общее: модель «Автомобиль», NMEA только RMC и GGA, скорость порта не трогается.
 * SBAS и QZSS — дополнения к GPS, в лимит частоты u-blox их не считает (5 Гц дано вместе с ними).
 */
enum class Preset(val title: String, val rateMs: Int, val gnss: Set<Gnss>) {
    /** Больше спутников; с Galileo предел M8 во flash — 3 Гц, поэтому 1 Гц. */
    NAVIGATOR("Навигатор", 1000, setOf(Gnss.GPS, Gnss.GLONASS, Gnss.GALILEO, Gnss.SBAS, Gnss.QZSS)),

    /** Быстрее реакция на развязках; GPS + ГЛОНАСС — предел M8 во flash ровно 5 Гц. */
    FAST("Быстрый", 200, setOf(Gnss.GPS, Gnss.GLONASS, Gnss.SBAS, Gnss.QZSS)),
}

/** USB GPS (UsbGps4Droid) строит координаты только из RMC и GGA; GSA, VTG, GLL он выбрасывает, GSV не читает. */
val USB_GPS_NMEA = setOf(NmeaMsg.RMC, NmeaMsg.GGA)
private const val NAVIGATOR_BAUD = 115200
private const val OVERLOAD = 0.9

class ReceiverSession(
    private val transport: GpsTransport,
    parent: CoroutineScope,
    private val onMessage: (String) -> Unit,
    private val onLost: (String) -> Unit,
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val client = UbxClient(transport)
    private val _state = MutableStateFlow(
        SessionState(
            transport.title,
            transport.subtitle,
            demo = transport is DemoTransport,
            bridge = transport.isBridge,
            initialBaud = transport.baud,
        ),
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    @Volatile private var closed = false
    private val rmcTimes = ArrayDeque<Long>()
    private val ggaTimes = ArrayDeque<Long>()
    private var rmc: Rmc? = null
    private var gga: Gga? = null
    private var pvtSupported = true
    private var satSupported = true
    private var startedNanos = 0L
    private val byteSamples = ArrayDeque<Pair<Long, Long>>()

    fun start() {
        startedNanos = System.nanoTime()
        transport.start(client::onData) { e ->
            if (!closed) scope.launch { onLost("Связь с адаптером потеряна: ${e.message ?: e.javaClass.simpleName}") }
        }
        scope.launch {
            client.packets.collect { if (it is Packet.Nmea) onNmea(it.sentence) }
        }
        scope.launch { init() }
        scope.launch { liveLoop() }
    }

    fun close() {
        closed = true
        job.cancel()
        scope.cancel()
        transport.close()
    }

    // --- чтение ---

    private suspend fun init() {
        val info = client.poll(Ubx.MON, Ubx.MON_VER, timeoutMs = 2000, retries = 2)?.let(ReceiverInfo::decode)
        // MON-VER длинный и в перегруженный порт может не пролезть — проверяем u-blox коротким запросом
        if (info == null && client.poll(Ubx.CFG, Ubx.CFG_RATE, retries = 4) == null) {
            _state.update {
                it.copy(loading = false, unsupported = "Приёмник не отвечает на команды UBX. Настраивать можно только u-blox.")
            }
            return
        }
        val gen = info?.generation
        if (info != null && gen != null && gen >= 9) {
            _state.update {
                it.copy(
                    info = info,
                    loading = false,
                    unsupported = "${info.chipName} настраивается по-другому (CFG-VALSET). В этой версии поддерживаются u-blox 6, 7 и M8.",
                )
            }
            return
        }
        _state.update { it.copy(info = info) }
        if (transport.isBridge && measureLoad() > OVERLOAD) {
            _state.update { it.copy(loading = false, overloadGate = true) }
            return
        }
        val cfg = readConfig()
        _state.update { it.copy(loading = false, current = cfg, draft = cfg) }
    }

    /** Загрузка порта за полторы секунды. */
    private suspend fun measureLoad(): Double {
        val baud = transport.baud ?: return 0.0
        val b0 = client.bytesReceived
        delay(1500)
        return (client.bytesReceived - b0) / 1.5 / (baud / 10.0)
    }

    /** Порт перегружен: поднять скорость до 115200 и прочитать настройки уже без потерь. */
    fun relievePort() {
        if (!_state.value.overloadGate) return
        scope.launch {
            _state.update { it.copy(overloadGate = false, loading = true, busy = "Меняю скорость…") }
            val port = client.poll(Ubx.CFG, Ubx.CFG_PRT, PortCfg.poll(PortCfg.UART1), retries = 4) {
                it.size >= 20 && it.u1(0) == PortCfg.UART1
            }?.let(PortCfg::decode)
            val failure = if (port == null) "Не удалось прочитать настройки порта" else changeBaud(port.copy(baud = NAVIGATOR_BAUD))
            // пока порт был перегружен, опросы PVT/SAT могли отключиться — пробуем снова
            pvtSupported = true
            satSupported = true
            _state.update { it.copy(busy = null) }
            val cfg = readConfig()
            _state.update { it.copy(loading = false, current = cfg, draft = cfg, unsaved = failure == null) }
            onMessage(failure ?: "Порт переключён на $NAVIGATOR_BAUD. Сохраните и выставьте $NAVIGATOR_BAUD в USB GPS")
        }
    }

    /**
     * Порт перегружен: 1 Гц и только RMC + GGA. Эти команды не зависят от текущих настроек, поэтому
     * уходят до чтения; ACK в перегруженный порт может не пролезть, отсюда повторы.
     */
    fun unloadPort() {
        if (!_state.value.overloadGate) return
        scope.launch {
            _state.update { it.copy(overloadGate = false, loading = true, busy = "Разгружаю порт…") }
            client.command(Ubx.CFG, Ubx.CFG_RATE, RateCfg(1000, 1, 1).encode(), attempts = 3)
            for (m in NmeaMsg.entries) client.command(Ubx.CFG, Ubx.CFG_MSG, CfgMsg.set(m, if (m in USB_GPS_NMEA) 1 else 0), attempts = 3)
            delay(1500)
            pvtSupported = true
            satSupported = true
            _state.update { it.copy(busy = null) }
            val cfg = readConfig()
            _state.update { it.copy(loading = false, current = cfg, draft = cfg, unsaved = true) }
            onMessage(
                if (cfg.rate?.measRateMs == 1000) "Порт разгружен: 1 Гц, только RMC и GGA. Проверьте и сохраните"
                else "Не удалось разгрузить порт — попробуйте поднять скорость",
            )
        }
    }

    /** Прочитать настройки на перегруженном порту как есть — медленно и с пропусками. */
    fun readAnyway() {
        if (!_state.value.overloadGate) return
        scope.launch {
            _state.update { it.copy(overloadGate = false, loading = true) }
            pvtSupported = true
            satSupported = true
            val cfg = readConfig()
            _state.update { it.copy(loading = false, current = cfg, draft = cfg) }
        }
    }

    private suspend fun readConfig(): ReceiverConfig {
        // у перегруженного порта за мостом приёмник теряет часть ответов — переспрашиваем чаще
        val retries = if (transport.isBridge) 4 else 1
        val rate = client.poll(Ubx.CFG, Ubx.CFG_RATE, retries = retries)?.let(RateCfg::decode)
        val dyn = client.poll(Ubx.CFG, Ubx.CFG_NAV5, retries = retries)?.let(Nav5::decodeDynModel)
        val gnss = client.poll(Ubx.CFG, Ubx.CFG_GNSS, timeoutMs = 2000, retries = retries)?.let(GnssCfg::decode)
        val nmea = buildMap {
            for (m in NmeaMsg.entries) {
                val reply = client.poll(Ubx.CFG, Ubx.CFG_MSG, CfgMsg.poll(m), retries = retries) { CfgMsg.isFor(it, m) }
                reply?.let { CfgMsg.rate(it, transport.msgPort) }?.let { put(m, it) }
            }
        }
        val port = if (transport.isBridge) {
            client.poll(Ubx.CFG, Ubx.CFG_PRT, PortCfg.poll(PortCfg.UART1), retries = retries) { it.size >= 20 && it.u1(0) == PortCfg.UART1 }
                ?.let(PortCfg::decode)
        } else {
            null
        }
        return ReceiverConfig(rate, dyn, gnss, nmea, port)
    }

    // --- черновик ---

    private fun editDraft(transform: (ReceiverConfig) -> ReceiverConfig) {
        _state.update { s -> if (s.editable && s.draft != null) s.copy(draft = transform(s.draft)) else s }
    }

    fun setRate(measRateMs: Int) = editDraft { d -> d.copy(rate = d.rate?.copy(measRateMs = measRateMs, navRate = 1)) }

    fun setBaud(baud: Int) = editDraft { d -> d.copy(port = d.port?.copy(baud = baud)) }

    fun setDynModel(model: DynModel) = editDraft { d -> d.copy(dynModel = d.dynModel?.let { model.code }) }

    fun toggleNmea(m: NmeaMsg) = editDraft { d ->
        val r = d.nmea[m] ?: return@editDraft d
        d.copy(nmea = d.nmea + (m to if (r > 0) 0 else 1))
    }

    fun toggleGnss(g: Gnss) {
        if (g == Gnss.GPS) {
            onMessage("GPS выключить нельзя — без него остальные системы работают хуже")
            return
        }
        val s = _state.value
        val gnss = s.draft?.gnss ?: return
        if (!s.editable) return
        val on = !gnss.isEnabled(g)
        var next = gnss.withEnabled(g, on)
        val rival = when (g) {
            Gnss.GLONASS -> Gnss.BEIDOU
            Gnss.BEIDOU -> Gnss.GLONASS
            else -> null
        }
        if (on && rival != null && next.isEnabled(rival)) {
            next = next.withEnabled(rival, false)
            onMessage("ГЛОНАСС и BeiDou на M8 одновременно не работают — ${rival.title} выключен")
        }
        editDraft { it.copy(gnss = next) }
    }

    fun applyPreset(preset: Preset) {
        val s = _state.value
        editDraft { d ->
            d.copy(
                rate = d.rate?.copy(measRateMs = preset.rateMs, navRate = 1),
                dynModel = d.dynModel?.let { DynModel.AUTOMOTIVE.code },
                gnss = d.gnss?.let { g ->
                    Gnss.entries.filter { s.supports(it) }.fold(g) { acc, sys -> acc.withEnabled(sys, sys in preset.gnss) }
                },
                // остальное NMEA — лишний трафик; без него и 9600 бод хватает, скорость в USB GPS менять не нужно
                nmea = d.nmea.mapValues { (m, r) -> if (m in USB_GPS_NMEA) maxOf(r, 1) else 0 },
            )
        }
        if (_state.value.dirty) onMessage("Пресет «${preset.title}» выбран — нажмите «Применить»")
        else onMessage("Приёмник уже настроен как «${preset.title}»")
    }

    fun discardDraft() = _state.update { it.copy(draft = it.current) }

    // --- запись ---

    fun apply() {
        val s = _state.value
        val cur = s.current ?: return
        val d = s.draft ?: return
        if (!s.editable || !s.dirty) return
        scope.launch {
            _state.update { it.copy(busy = "Применяю…") }
            val steps = buildList {
                if (d.rate != null && d.rate != cur.rate) add(Step("частота", Ubx.CFG_RATE, d.rate.encode()))
                if (d.dynModel != null && d.dynModel != cur.dynModel) add(Step("модель движения", Ubx.CFG_NAV5, Nav5.encodeDynModel(d.dynModel)))
                if (d.gnss != null && d.gnss != cur.gnss) add(Step("спутниковые системы", Ubx.CFG_GNSS, d.gnss.encode(), 4000))
                for (m in NmeaMsg.entries) {
                    val r = d.nmea[m] ?: continue
                    if (r != cur.nmea[m]) add(Step("NMEA ${m.name}", Ubx.CFG_MSG, CfgMsg.set(m, r)))
                }
            }
            var applied = 0
            var failure: String? = null
            for (step in steps) {
                when (client.command(Ubx.CFG, step.id, step.payload, step.timeoutMs)) {
                    Ack.ACK -> applied++
                    Ack.NAK -> failure = "Приёмник отклонил: ${step.name}"
                    Ack.TIMEOUT -> failure = "Нет ответа на команду: ${step.name}"
                }
                if (failure != null) break
            }
            // скорость порта — последней: остальные команды уходят на старой
            val newPort = d.port
            if (failure == null && newPort != null && newPort != cur.port) {
                _state.update { it.copy(busy = "Меняю скорость…") }
                failure = changeBaud(newPort)
                if (failure == null) applied++
            }
            pvtSupported = true
            satSupported = true
            _state.update { it.copy(busy = "Проверяю…") }
            val fresh = readConfig().orElse(cur)
            _state.update {
                it.copy(busy = null, current = fresh, draft = fresh, unsaved = it.unsaved || applied > 0)
            }
            onMessage(failure ?: "Применено. Проверьте частоту справа и нажмите «Сохранить в приёмник»")
        }
    }

    /** Меняет скорость UART приёмника и ГУ. null — успешно, иначе текст ошибки. */
    private suspend fun changeBaud(port: PortCfg): String? {
        val old = transport.baud ?: return "Скорость меняется только у адаптеров с мостом"
        val ack = client.command(Ubx.CFG, Ubx.CFG_PRT, port.encode(), timeoutMs = 1500, attempts = 1) {
            // дать команде уйти на старой скорости, затем переключиться: ACK придёт уже на новой
            delay(150)
            withContext(Dispatchers.IO) { transport.setBaud(port.baud) }
        }
        if (ack == Ack.ACK) return null
        if (ack == Ack.TIMEOUT && client.poll(Ubx.CFG, Ubx.CFG_RATE, timeoutMs = 1000) != null) return null
        withContext(Dispatchers.IO) { transport.setBaud(old) }
        return if (client.poll(Ubx.CFG, Ubx.CFG_RATE, timeoutMs = 1000) != null) {
            "Приёмник не сменил скорость порта"
        } else {
            "Связь потеряна после смены скорости — переподключитесь"
        }
    }

    fun save() {
        if (!_state.value.editable) return
        scope.launch {
            _state.update { it.copy(busy = "Сохраняю…") }
            val ack = client.command(Ubx.CFG, Ubx.CFG_CFG, CfgCfg.SAVE, 3000)
            _state.update { it.copy(busy = null, unsaved = it.unsaved && ack != Ack.ACK) }
            onMessage(
                when (ack) {
                    Ack.ACK -> "Сохранено в память приёмника — настройки переживут выключение"
                    Ack.NAK -> "Приёмник отклонил сохранение"
                    Ack.TIMEOUT -> "Нет ответа на сохранение"
                },
            )
        }
    }

    fun resetToFactory() {
        if (!_state.value.editable) return
        scope.launch {
            _state.update { it.copy(busy = "Сбрасываю…") }
            val ack = client.command(Ubx.CFG, Ubx.CFG_CFG, CfgCfg.RESET, 3000)
            delay(1500)
            // заводская скорость UART — 9600
            if (transport.isBridge) withContext(Dispatchers.IO) { transport.setBaud(9600) }
            pvtSupported = true
            satSupported = true
            val fresh = readConfig()
            val lost = fresh.rate == null && fresh.nmea.isEmpty()
            _state.update { s ->
                if (lost) s.copy(busy = null) else s.copy(busy = null, current = fresh, draft = fresh, unsaved = false)
            }
            onMessage(
                when {
                    lost -> "Приёмник перестал отвечать — переподключитесь"
                    ack == Ack.NAK -> "Приёмник отклонил сброс"
                    else -> "Заводские настройки восстановлены"
                },
            )
        }
    }

    private class Step(val name: String, val id: Int, val payload: ByteArray, val timeoutMs: Long = 1500)

    private fun ReceiverConfig.orElse(old: ReceiverConfig) = ReceiverConfig(
        rate = rate ?: old.rate,
        dynModel = dynModel ?: old.dynModel,
        gnss = gnss ?: old.gnss,
        nmea = old.nmea + nmea,
        port = port ?: old.port,
    )

    // --- живой статус ---

    private fun onNmea(line: String) {
        val now = System.nanoTime()
        when (Nmea.type(line)) {
            "RMC" -> {
                rmcTimes.addLast(now)
                rmc = parseRmc(Nmea.fields(line))
            }
            "GGA" -> {
                ggaTimes.addLast(now)
                gga = parseGga(Nmea.fields(line))
            }
        }
    }

    /** Частота по моментам прихода строк за последние 3 с. */
    private fun rate(times: ArrayDeque<Long>, now: Long): Double? {
        while (times.isNotEmpty() && now - times.first() > 3_000_000_000L) times.removeFirst()
        if (times.size < 2) return if (times.size == 1 && now - startedNanos > 2_500_000_000L) 1.0 / 3 else null
        return (times.size - 1) / ((times.last() - times.first()) / 1e9)
    }

    private suspend fun liveLoop() {
        var pvtMisses = 0
        var satMisses = 0
        var tick = 0
        while (scope.isActive) {
            delay(1000)
            tick++
            val s = _state.value
            var polled: Pvt? = null
            var sats = s.live.sats
            if (!s.loading && s.busy == null && s.unsupported == null) {
                if (pvtSupported) {
                    polled = client.poll(Ubx.NAV, Ubx.NAV_PVT, timeoutMs = 700, retries = 0)?.let(Pvt::decode)
                    pvtMisses = if (polled == null) pvtMisses + 1 else 0
                    if (pvtMisses >= 5 && s.live.hAccM == null) pvtSupported = false
                }
                // NAV-SAT — сотни байт; на мосту опрашиваем реже, чтобы не забивать порт
                if (satSupported && (!transport.isBridge || tick % 3 == 0)) {
                    val reply = client.poll(Ubx.NAV, Ubx.NAV_SAT, timeoutMs = 700, retries = 0)
                    if (reply != null) sats = Sat.decodeAll(reply)
                    satMisses = if (reply == null) satMisses + 1 else 0
                    if (satMisses >= 5 && sats == null) satSupported = false
                }
            }
            val pvt = polled
            val now = System.nanoTime()
            val rmcRate = rate(rmcTimes, now)
            val ggaRate = rate(ggaTimes, now)
            val noData = now - maxOf(client.lastDataNanos, startedNanos) > 3_000_000_000L
            byteSamples.addLast(now to client.nmeaBytesReceived)
            while (byteSamples.size > 4) byteSamples.removeFirst()
            val portLoad = transport.baud?.let { baud ->
                val (t0, b0) = byteSamples.first()
                val (t1, b1) = byteSamples.last()
                if (t1 > t0) (b1 - b0) / ((t1 - t0) / 1e9) / (baud / 10.0) else null
            }
            val g = gga
            val r = rmc
            val live = LiveStatus(
                nmeaRate = rmcRate ?: ggaRate,
                noData = noData,
                fixType = pvt?.fixType ?: g?.let { if (it.quality > 0) 3 else 0 },
                fixOk = pvt?.fixOk ?: (r?.valid == true || (g?.quality ?: 0) > 0),
                numSvUsed = pvt?.numSv ?: g?.numSv,
                hAccM = pvt?.takeIf { it.fixOk }?.hAccM,
                speedKmh = pvt?.speedKmh ?: r?.speedKmh,
                sAccKmh = pvt?.takeIf { it.fixOk }?.sAccKmh,
                hdop = g?.hdop,
                sats = sats,
                errors = client.errors,
                portLoad = portLoad,
            )
            _state.update { it.copy(live = live) }
        }
    }
}
