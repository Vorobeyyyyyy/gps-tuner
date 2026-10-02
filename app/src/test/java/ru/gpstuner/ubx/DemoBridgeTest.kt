package ru.gpstuner.ubx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.gpstuner.transport.DemoTransport

/** Адаптер с мостом: 9600 бод не пропускают 10 Гц, смена скорости по CFG-PRT. */
class DemoBridgeTest {
    private lateinit var demo: DemoTransport
    private lateinit var client: UbxClient

    @Before fun setUp() {
        demo = DemoTransport(bridge = true)
        client = UbxClient(demo)
        demo.start(client::onData) { throw it }
    }

    @After fun tearDown() = demo.close()

    private suspend fun bytesPerSecond(): Double {
        val b0 = client.bytesReceived
        delay(2000)
        return (client.bytesReceived - b0) / 2.0
    }

    @Test fun changeBaud() = runBlocking {
        // на 9600 поток упирается в потолок порта (960 байт/с), лишнее теряется
        assertTrue(bytesPerSecond() in 900.0..1500.0)

        // MON-VER (220 байт) в перегруженный порт пролезает не всегда — как у настоящего приёмника
        val port = client.poll(Ubx.CFG, Ubx.CFG_PRT, PortCfg.poll(1), retries = 4) { it.size >= 20 }?.let(PortCfg::decode)
        assertEquals(9600, port?.baud)

        val ack = client.command(Ubx.CFG, Ubx.CFG_PRT, port!!.copy(baud = 115200).encode(), attempts = 1) {
            delay(150)
            withContext(Dispatchers.IO) { demo.setBaud(115200) }
        }
        assertEquals(Ack.ACK, ack)
        assertEquals(115200, client.poll(Ubx.CFG, Ubx.CFG_PRT, PortCfg.poll(1)) { it.size >= 20 }?.let(PortCfg::decode)?.baud)

        // при 115200 влезает всё: 10 Гц с GSV по трём системам — больше 2 КБ/с
        assertTrue(bytesPerSecond() > 2000)

        // ГУ на старой скорости — от приёмника только мусор
        demo.setBaud(9600)
        assertNull(client.poll(Ubx.CFG, Ubx.CFG_RATE, timeoutMs = 700, retries = 0))
        demo.setBaud(115200)
        assertNotNull(client.poll(Ubx.CFG, Ubx.CFG_RATE))
    }
}
