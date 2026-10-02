package ru.gpstuner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gpstuner.transport.DemoTransport
import ru.gpstuner.ubx.DynModel
import ru.gpstuner.ubx.Gnss
import ru.gpstuner.ubx.NmeaMsg
import java.util.concurrent.Executors

/** Сценарии кнопок экрана «Приёмник» против эмулятора — та же логика, что на ГУ. */
class ReceiverSessionTest {
    // один поток, как главный поток Android
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val messages = mutableListOf<String>()
    private var session: ReceiverSession? = null

    @After fun tearDown() {
        session?.close()
        scope.cancel()
        dispatcher.close()
    }

    private fun start(t: DemoTransport) = ReceiverSession(t, scope, { messages += it }, { messages += it }).also {
        session = it
        it.start()
    }

    private suspend fun ReceiverSession.waitFor(timeoutMs: Long = 30_000, what: (SessionState) -> Boolean) =
        withTimeout(timeoutMs) {
            while (!what(state.value)) delay(50)
            state.value
        }

    @Test fun presetApplySaveReset() = runBlocking {
        val s = start(DemoTransport())
        var st = s.waitFor { !it.loading }
        assertEquals(100, st.current?.rate?.measRateMs)

        s.applyPreset(Preset.NAVIGATOR)
        assertTrue(s.state.value.dirty)
        s.apply()
        st = s.waitFor { it.busy == null && it.unsaved && !it.dirty }
        val cfg = st.current!!
        assertEquals(1000, cfg.rate?.measRateMs)
        assertEquals(DynModel.AUTOMOTIVE.code, cfg.dynModel)
        assertTrue(cfg.gnss!!.isEnabled(Gnss.GALILEO))
        assertFalse(cfg.gnss!!.isEnabled(Gnss.BEIDOU))
        assertEquals(mapOf(NmeaMsg.RMC to 1, NmeaMsg.GGA to 1, NmeaMsg.GSA to 0, NmeaMsg.GSV to 0, NmeaMsg.VTG to 0, NmeaMsg.GLL to 0), cfg.nmea)

        s.save()
        s.waitFor { it.busy == null && !it.unsaved }

        s.resetToFactory()
        st = s.waitFor { it.busy == null && it.current?.dynModel == DynModel.PORTABLE.code }
        assertTrue(st.current!!.nmea.values.all { it == 1 })
        assertFalse(st.current!!.gnss!!.isEnabled(Gnss.GALILEO))
        assertFalse(st.unsaved)
    }

    @Test fun fastPreset() = runBlocking {
        val s = start(DemoTransport())
        s.waitFor { !it.loading }
        s.applyPreset(Preset.FAST)
        s.apply()
        val st = s.waitFor { it.busy == null && it.unsaved && !it.dirty }
        val cfg = st.current!!
        assertEquals(200, cfg.rate?.measRateMs)
        assertEquals(DynModel.AUTOMOTIVE.code, cfg.dynModel)
        val gnss = cfg.gnss!!
        assertTrue(gnss.isEnabled(Gnss.GPS) && gnss.isEnabled(Gnss.GLONASS))
        assertFalse(gnss.isEnabled(Gnss.GALILEO) || gnss.isEnabled(Gnss.BEIDOU))
        assertEquals(setOf(NmeaMsg.RMC, NmeaMsg.GGA), cfg.nmea.filterValues { it > 0 }.keys)
        // 5 Гц — ровно предел M8 во flash для GPS + ГЛОНАСС, предупреждения нет
        assertEquals(5, st.info!!.maxRateHz(gnss))

        // реальная частота RMC — около 5 в секунду
        delay(4000)
        val rate = s.state.value.live.nmeaRate!!
        assertTrue("частота RMC $rate", rate in 4.5..5.5)
    }

    @Test fun overloadedBridgeUnload() = runBlocking {
        val s = start(DemoTransport(bridge = true))
        s.waitFor { it.overloadGate }
        s.unloadPort()
        var st = s.waitFor(60_000) { !it.loading && it.current != null }
        assertEquals(1000, st.current?.rate?.measRateMs)
        assertEquals(setOf(NmeaMsg.RMC, NmeaMsg.GGA), st.current!!.nmea.filterValues { it > 0 }.keys)
        assertEquals(9600, st.current?.port?.baud)
        assertNull("скорость не менялась — USB GPS трогать не надо", st.baudChanged)

        // после разгрузки порт загружен слабо: RMC + GGA раз в секунду — около 15% от 9600
        delay(4000)
        st = s.state.value
        assertTrue("загрузка порта ${st.live.portLoad}", (st.live.portLoad ?: 1.0) < 0.3)
    }
}
