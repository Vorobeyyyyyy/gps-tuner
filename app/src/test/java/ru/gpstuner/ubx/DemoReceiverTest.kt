package ru.gpstuner.ubx

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.gpstuner.transport.DemoTransport

/** UbxClient против эмулятора приёмника: тот же путь, что и с настоящим адаптером. */
class DemoReceiverTest {
    private lateinit var demo: DemoTransport
    private lateinit var client: UbxClient
    private val rmcCount = java.util.concurrent.atomic.AtomicInteger()

    @Before fun setUp() {
        demo = DemoTransport()
        client = UbxClient(demo)
        demo.start({ data ->
            client.onData(data)
            rmcCount.addAndGet(String(data, Charsets.ISO_8859_1).split("RMC,").size - 1)
        }, { throw it })
    }

    @After fun tearDown() = demo.close()

    @Test fun fullScenario() = runBlocking {
        val info = client.poll(Ubx.MON, Ubx.MON_VER)?.let(ReceiverInfo::decode)
        assertEquals("u-blox M8", info?.chipName)

        // стартует как у пользователя: 10 Гц
        assertEquals(100, client.poll(Ubx.CFG, Ubx.CFG_RATE)?.let(RateCfg::decode)?.measRateMs)
        rmcCount.set(0)
        delay(1000)
        assertTrue("RMC за секунду при 10 Гц: ${rmcCount.get()}", rmcCount.get() in 8..12)

        assertEquals(Ack.ACK, client.command(Ubx.CFG, Ubx.CFG_RATE, RateCfg(1000, 1, 1).encode()))
        assertEquals(Ack.ACK, client.command(Ubx.CFG, Ubx.CFG_NAV5, Nav5.encodeDynModel(4)))
        assertEquals(1000, client.poll(Ubx.CFG, Ubx.CFG_RATE)?.let(RateCfg::decode)?.measRateMs)
        assertEquals(4, client.poll(Ubx.CFG, Ubx.CFG_NAV5)?.let(Nav5::decodeDynModel))
        rmcCount.set(0)
        delay(2100)
        assertTrue("RMC за 2 с при 1 Гц: ${rmcCount.get()}", rmcCount.get() in 2..3)

        // ГЛОНАСС + BeiDou одновременно — приёмник отказывает
        val gnss = client.poll(Ubx.CFG, Ubx.CFG_GNSS)?.let(GnssCfg::decode)
        assertNotNull(gnss)
        assertEquals(Ack.NAK, client.command(Ubx.CFG, Ubx.CFG_GNSS, gnss!!.withEnabled(Gnss.BEIDOU, true).encode()))

        // NMEA: выключаем GLL и читаем обратно
        assertEquals(Ack.ACK, client.command(Ubx.CFG, Ubx.CFG_MSG, CfgMsg.set(NmeaMsg.GLL, 0)))
        val gll = client.poll(Ubx.CFG, Ubx.CFG_MSG, CfgMsg.poll(NmeaMsg.GLL)) { CfgMsg.isFor(it, NmeaMsg.GLL) }
        assertEquals(0, gll?.let { CfgMsg.rate(it, 3) })

        // сохранение и сброс
        assertEquals(Ack.ACK, client.command(Ubx.CFG, Ubx.CFG_CFG, CfgCfg.SAVE))
        assertEquals(Ack.ACK, client.command(Ubx.CFG, Ubx.CFG_CFG, CfgCfg.RESET))
        assertEquals(DynModel.PORTABLE.code, client.poll(Ubx.CFG, Ubx.CFG_NAV5)?.let(Nav5::decodeDynModel))

        // живой статус
        assertNotNull(client.poll(Ubx.NAV, Ubx.NAV_PVT)?.let(Pvt::decode))
        assertTrue(client.poll(Ubx.NAV, Ubx.NAV_SAT)?.let(Sat::decodeAll)!!.isNotEmpty())
        assertEquals(0, client.errors)
    }
}
