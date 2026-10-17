package io.github.kevinbuckham.minilogsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class LiveLogFormatTest {

    // Lines in the shape of a generated rusEFI ini, including its quirks: tabs,
    // trailing comments, a scalar with no units/scale, a commented-out #if, and an
    // expression channel.
    private val ini = """
        [TunerStudio]
        	queryCommand	= "S"
        	signature= "rusEFI master.2026.10.17.uaefi_pro.1333794255" ; signature is expected
        [Constants]
        	blockingFactor = 1024 ; max chunk size
        [OutputChannels]
        	ochGetCommand	= "O%2o%2c"
        	ochBlockSize	= 2220
        ;#if CONN_SLOW
        sd_present = bits, U32, 0, [0:0]
        idleState = bits, S32, 1896, [0:2], "not important"
        RPMValue = scalar, U16, 4, "RPM", 1, 0
        speedToRpmRatio = scalar, U16, 8, "value", 0.01, 0
        internalMcuTemperature = scalar, S08, 10, "deg C", 1, 0
        tpsFrom = scalar, F32, 1536
        coilDutyCycle = { 100 * dwell / period }
        [Datalog]
        	entry = time,				"Time",		float,  "%.3f"
        entry = sd_present, "SD: Present", int,    "%d"
        entry = RPMValue, "RPM", int,    "%d"
        entry = speedToRpmRatio, "Gearbox Ratio", float,  "%.3f"
        entry = coilDutyCycle, "Coil duty", float, "%.1f"
    """.trimIndent()

    private fun parse() = IniModel.parse(ini.reader().buffered())

    @Test fun parsesHeaderValues() {
        val m = parse()
        assertEquals("rusEFI master.2026.10.17.uaefi_pro.1333794255", m.signature)
        assertEquals(2220, m.blockSize)
        assertEquals(1024, m.blockingFactor)
    }

    @Test fun parsesChannels() {
        val m = parse()
        assertEquals(IniModel.Channel.Scalar("U16", 4, "RPM", 1.0, 0.0), m.channels["RPMValue"])
        assertEquals(IniModel.Channel.Scalar("U16", 8, "value", 0.01, 0.0), m.channels["speedToRpmRatio"])
        assertEquals(IniModel.Channel.Scalar("F32", 1536, "", 1.0, 0.0), m.channels["tpsFrom"])
        assertEquals(IniModel.Channel.Bits("U32", 0, 0, 0), m.channels["sd_present"])
        assertEquals(IniModel.Channel.Bits("S32", 1896, 0, 2), m.channels["idleState"])
        assertNull("expression channels are not evaluated", m.channels["coilDutyCycle"])
        assertNull(m.channels["ochGetCommand"])
    }

    @Test fun parsesDatalog() {
        val d = parse().datalog
        assertEquals(listOf("time", "sd_present", "RPMValue", "speedToRpmRatio", "coilDutyCycle"),
                     d.map { it.channel })
        assertEquals("Gearbox Ratio", d[3].label)
        assertEquals(3, d[3].digits)
        assertEquals(0, d[2].digits)
    }

    @Test fun signatureToUrl() {
        assertEquals(
            "https://rusefi.com/online/ini/rusefi/master/2026/10/17/uaefi_pro/1333794255.ini",
            IniStore.urlFor("rusEFI master.2026.10.17.uaefi_pro.1333794255"))
        assertNull(IniStore.urlFor("speeduino 202501"))
        assertNull(IniStore.urlFor("rusEFI custom"))
    }

    @Test fun outputRequestIsLittleEndianAndFramed() {
        val f = TsPacket.outputRequest(0x0400, 0x01AC)
        // len (BE) = 5, 'O', offset LE, count LE, crc
        assertEquals(listOf(0, 5, 'O'.code, 0x00, 0x04, 0xAC, 0x01),
                     f.take(7).map { it.toInt() and 0xFF })
        assertEquals(2 + 5 + 4, f.size)
    }

    @Test fun mlgHeaderAndRecordsAreReadable() {
        val fields = listOf(
            MlgWriter.Field("Time", "s", MlgWriter.F32, 1f, 0f, 3),
            MlgWriter.Field("RPM", "RPM", MlgWriter.U16, 1f, 0f, 0),
            MlgWriter.Field("SD: Present", "", MlgWriter.U08, 1f, 0f, 0)
        )
        val out = ByteArrayOutputStream()
        val w = MlgWriter(out, fields, "\"test\"", 1_760_000_000L)
        assertEquals(4 + 2 + 1, w.recordLength)
        val rec = ByteBuffer.allocate(7).putFloat(0.5f).putShort(3000).put(1).array()
        // Counter must wrap past 255. Start at 10 ms: a record whose counter AND
        // timestamp are both 0 reads as padding to MlgTrim, as it would in a real log.
        repeat(300) { w.writeRecord(rec, (it + 1) * 10_000L) }
        val b = out.toByteArray()

        val h = MlgTrim.parseHeader(b, b.size)
        assertNotNull(h)
        val infoStart = MlgWriter.HEADER + MlgWriter.FIELD * fields.size
        assertEquals(infoStart + "\"test\"".length + 1, h!!.dataBegin)
        assertEquals(7 + 5, h.stride)
        assertEquals(1_760_000_000L, MlgTrim.timestampOf(b, b.size))
        assertEquals(h.dataBegin + 300 * h.stride, b.size)

        // Field descriptor 2 ("RPM"): type, name, units at the spec's offsets.
        val f1 = MlgWriter.HEADER + MlgWriter.FIELD
        assertEquals(MlgWriter.U16, b[f1].toInt())
        assertEquals("RPM", String(b, f1 + 1, 3))
        assertEquals(0, b[f1 + 1 + 3].toInt())

        var prev: Int? = null
        var off = h.dataBegin
        while (off < b.size) {
            assertEquals(MlgTrim.Verdict.DATA, MlgTrim.classify(b, off, prev))
            var sum = 0
            for (i in 0 until 7) sum += b[off + 4 + i]
            assertEquals(sum.toByte(), b[off + 4 + 7])
            prev = MlgTrim.counterOf(b, off)
            off += h.stride
        }
    }

    @Test fun syncScopeCutoffs() {
        val now = java.time.LocalDateTime.of(2026, 10, 17, 12, 0)
        val sizes = mapOf(
            "re_261017_080000.mlg" to 10L,   // today
            "re_261016_080000.mlg" to 10L,   // yesterday, > 24 h ago
            "re_261001_080000.mlg" to 10L,   // weeks ago
            "re_0042.mlg" to 10L             // counter name: never skipped
        )
        assertTrue(SyncScope.isTooOld("re_261016_080000.mlg", 1, now))
        assertTrue(!SyncScope.isTooOld("re_261016_080000.mlg", 2, now))
        assertTrue(!SyncScope.isTooOld("re_0042.mlg", 0, now))
        val opts = SyncScope.options(sizes, now)
        assertEquals(listOf(2, 3, 3, 1, 4), opts.map { it.count })   // 1d, 2d, 7d, none, all
        assertEquals(null, opts.last().days)
        assertEquals(listOf("re_261017_080000.mlg", "re_261016_080000.mlg",
                            "re_261001_080000.mlg", "re_0042.mlg"),
                     SyncScope.newestFirst(sizes.keys.toList()) { it })
    }
}
