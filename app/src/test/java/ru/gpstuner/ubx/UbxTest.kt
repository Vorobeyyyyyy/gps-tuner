package ru.gpstuner.ubx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

class FrameTest {
    // Кадры, проверенные вручную и отправленные скриптом gps/ublox_fix.sh
    @Test fun rate1Hz() = assertEquals(
        "B5 62 06 08 06 00 E8 03 01 00 01 00 01 39",
        hex(Ubx.frame(Ubx.CFG, Ubx.CFG_RATE, RateCfg(1000, 1, 1).encode())),
    )

    @Test fun nav5Automotive() = assertEquals(
        "B5 62 06 24 24 00 01 00 04" + " 00".repeat(33) + " 53 70",
        hex(Ubx.frame(Ubx.CFG, Ubx.CFG_NAV5, Nav5.encodeDynModel(DynModel.AUTOMOTIVE.code))),
    )

    @Test fun save() = assertEquals(
        "B5 62 06 09 0D 00 00 00 00 00 FF FF 00 00 00 00 00 00 17 31 BF",
        hex(Ubx.frame(Ubx.CFG, Ubx.CFG_CFG, CfgCfg.SAVE)),
    )

    @Test fun reset() = assertEquals(
        "B5 62 06 09 0D 00 FF FF 00 00 00 00 00 00 FF FF 00 00 17 2F AE",
        hex(Ubx.frame(Ubx.CFG, Ubx.CFG_CFG, CfgCfg.RESET)),
    )

    @Test fun nmeaMsgShortForm() = assertEquals(
        "B5 62 06 01 03 00 F0 01 00 FB 11",
        hex(Ubx.frame(Ubx.CFG, Ubx.CFG_MSG, CfgMsg.set(NmeaMsg.GLL, 0))),
    )
}

class StreamParserTest {
    private val rmc = "\$GNRMC,083559.00,A,4717.11437,N,00833.91522,E,0.004,77.52,091202,,,A*49"
    private val ack = Ubx.frame(Ubx.ACK, Ubx.ACK_ACK, byteArrayOf(0x06, 0x08))

    private fun collect(chunks: List<ByteArray>): Pair<List<Packet>, Int> {
        val out = mutableListOf<Packet>()
        val p = StreamParser { out += it }
        chunks.forEach { p.feed(it) }
        return out to p.errors
    }

    @Test fun mixedStreamByteByByte() {
        val stream = "garbage".toByteArray() + "$rmc\r\n".toByteArray() + ack + "$rmc\r\n".toByteArray()
        val (out, errors) = collect(stream.map { byteArrayOf(it) })
        assertEquals(3, out.size)
        assertEquals(rmc, (out[0] as Packet.Nmea).sentence)
        val msg = (out[1] as Packet.Ubx).msg
        assertTrue(msg.isAck(0x06, 0x08))
        assertEquals(0, errors)
    }

    @Test fun badChecksumSkipped() {
        val broken = ack.copyOf().also { it[it.size - 1] = 0 }
        val (out, errors) = collect(listOf(broken + ack))
        assertEquals(1, out.size)
        assertEquals(1, errors)
    }

    @Test fun nmeaCutByUbx() {
        val (out, errors) = collect(listOf("\$GNGGA,0835".toByteArray() + ack + "$rmc\r\n".toByteArray()))
        assertEquals(listOf("UBX", "NMEA"), out.map { if (it is Packet.Ubx) "UBX" else "NMEA" })
        assertEquals(1, errors)
    }

    @Test fun nmeaParsing() {
        assertTrue(Nmea.checksumOk(rmc))
        assertFalse(Nmea.checksumOk(rmc.replace("A*49", "A*48")))
        assertEquals("RMC", Nmea.type(rmc))
        val r = parseRmc(Nmea.fields(rmc))
        assertTrue(r.valid)
        assertEquals(0.004 * 1.852, r.speedKmh!!, 1e-9)
        assertEquals(Nmea.build("GPTXT,01"), "\$GPTXT,01*${"%02X".format("GPTXT,01".fold(0) { a, c -> a xor c.code })}\r\n")
    }
}

class MessagesTest {
    private fun msg(cls: Int, id: Int, payload: ByteArray) = UbxMessage(cls, id, payload)

    @Test fun gnssRoundTrip() {
        val payload = bytes(
            "00 20 20 03 " +
                "00 08 10 00 01 00 01 01 " + // GPS вкл
                "02 04 08 00 00 00 01 01 " + // Galileo выкл
                "06 08 0E 00 01 00 01 01", // ГЛОНАСС вкл
        )
        val cfg = GnssCfg.decode(msg(Ubx.CFG, Ubx.CFG_GNSS, payload))
        assertTrue(cfg.isEnabled(Gnss.GPS))
        assertFalse(cfg.isEnabled(Gnss.GALILEO))
        assertEquals(2, cfg.majorEnabled())
        assertEquals(hex(payload), hex(cfg.encode()))
        val on = cfg.withEnabled(Gnss.GALILEO, true)
        assertTrue(on.isEnabled(Gnss.GALILEO))
        assertEquals(0x01010001L, on.blocks[1].flags)
    }

    @Test fun enablingKeepsSignalMask() {
        val b = GnssBlock(Gnss.BEIDOU.id, 0, 0, 0, 0).withEnabled(true)
        assertEquals(0x00010001L, b.flags)
        assertEquals(8, b.maxTrkCh)
    }

    @Test fun monVer() {
        val ext = listOf("ROM BASE 3.01 (107888)", "FWVER=SPG 3.01", "PROTVER=18.00", "MOD=NEO-M8N-0", "GPS;GLO;GAL;BDS", "SBAS;IMES;QZSS")
        val p = ByteArray(40 + 30 * ext.size)
        "ROM CORE 3.01 (107888)".toByteArray().copyInto(p, 0)
        "00080000".toByteArray().copyInto(p, 30)
        ext.forEachIndexed { i, s -> s.toByteArray().copyInto(p, 40 + 30 * i) }
        val info = ReceiverInfo.decode(msg(Ubx.MON, Ubx.MON_VER, p))
        assertEquals(8, info.generation)
        assertEquals("u-blox M8", info.chipName)
        assertEquals("SPG 3.01", info.firmware)
        assertEquals("18.00", info.protocol)
        assertEquals("NEO-M8N-0", info.module)
        assertEquals(setOf(Gnss.GPS, Gnss.GLONASS, Gnss.GALILEO, Gnss.BEIDOU, Gnss.SBAS, Gnss.QZSS), info.supportedGnss)
    }

    @Test fun monVerUblox7() {
        val ext = listOf("1.00 (59842)", "PROTVER 14.00", "GPS;SBAS;GLO;QZSS")
        val p = ByteArray(40 + 30 * ext.size)
        "1.00 (59842)".toByteArray().copyInto(p, 0)
        "00070000".toByteArray().copyInto(p, 30)
        ext.forEachIndexed { i, s -> s.toByteArray().copyInto(p, 40 + 30 * i) }
        val info = ReceiverInfo.decode(msg(Ubx.MON, Ubx.MON_VER, p))
        assertEquals("u-blox 7", info.chipName)
        assertEquals("14.00", info.protocol)
        assertFalse(info.supportedGnss!!.contains(Gnss.GALILEO))
    }

    @Test fun portCfgRoundTrip() {
        // UART1, 8N1, 9600, вход UBX+NMEA+RTCM, выход UBX+NMEA — типичный ответ M8
        val payload = bytes("01 00 00 00 C0 08 00 00 80 25 00 00 07 00 03 00 00 00 00 00")
        val port = PortCfg.decode(msg(Ubx.CFG, Ubx.CFG_PRT, payload))!!
        assertEquals(9600, port.baud)
        assertEquals(hex(payload), hex(port.encode()))
        assertEquals("01 00 00 00 C0 08 00 00 00 C2 01 00 07 00 03 00 00 00 00 00", hex(port.copy(baud = 115200).encode()))
    }

    @Test fun rateLimits() {
        fun gnss(vararg on: Gnss) = GnssCfg(0, 32, 32, Gnss.entries.map { GnssBlock(it.id, 4, 8, 0, if (it in on) 0x01010001 else 0x01010000) })
        val flash = ReceiverInfo("EXT CORE 3.01 (111141)", "00080000", emptyList())
        val rom = ReceiverInfo("ROM CORE 3.01 (107888)", "00080000", emptyList())
        assertEquals(true, flash.runsFromFlash)
        assertEquals(3, flash.maxRateHz(gnss(Gnss.GPS, Gnss.GLONASS, Gnss.GALILEO)))
        assertEquals(5, flash.maxRateHz(gnss(Gnss.GPS, Gnss.GLONASS)))
        assertEquals(10, flash.maxRateHz(gnss(Gnss.GPS)))
        assertEquals(10, rom.maxRateHz(gnss(Gnss.GPS, Gnss.GLONASS)))
        assertEquals(18, rom.maxRateHz(gnss(Gnss.GPS)))
    }

    @Test fun cfgMsgRates() {
        val r = msg(Ubx.CFG, Ubx.CFG_MSG, bytes("F0 04 01 01 00 01 00 00"))
        assertTrue(CfgMsg.isFor(r, NmeaMsg.RMC))
        assertEquals(1, CfgMsg.rate(r, 3))
        assertEquals(0, CfgMsg.rate(r, 2))
        assertNull(CfgMsg.rate(msg(Ubx.CFG, Ubx.CFG_MSG, bytes("F0 04")), 3))
    }

    @Test fun pvt() {
        val p = Ubx.payload(92) {
            put(20, 3)
            put(21, 1)
            put(23, 17)
            putInt(40, 2100)
            putInt(60, 16_667)
            putInt(68, 150)
            putShort(76, 130)
        }
        val pvt = Pvt.decode(msg(Ubx.NAV, Ubx.NAV_PVT, p))!!
        assertEquals(3, pvt.fixType)
        assertTrue(pvt.fixOk)
        assertEquals(17, pvt.numSv)
        assertEquals(2.1, pvt.hAccM, 1e-9)
        assertEquals(60.0, pvt.speedKmh, 0.01)
        assertEquals(1.3, pvt.pDop, 1e-9)
    }

    @Test fun navSat() {
        val p = Ubx.payload(8 + 24) {
            put(5, 2)
            put(8, 0); put(9, 5); put(10, 41); putInt(16, 0x0F)
            put(20, 6); put(21, 12); put(22, 22); putInt(28, 0x04)
        }
        val sats = Sat.decodeAll(msg(Ubx.NAV, Ubx.NAV_SAT, p))
        assertEquals(listOf(Sat(0, 5, 41, true), Sat(6, 12, 22, false)), sats)
    }
}
