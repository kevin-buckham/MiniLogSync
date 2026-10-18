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

    @Test fun syncScopeCutoffsAreMeasuredFromTheNewestLog() {
        val sizes = mapOf(
            "re_261017_080000.mlg" to 10L,   // newest
            "re_261016_090000.mlg" to 10L,   // 23 h before it
            "re_261015_070000.mlg" to 10L,   // 2 days 1 h before it
            "re_261001_080000.mlg" to 10L,   // weeks before
            "re_0042.mlg" to 10L             // counter name: never skipped
        )
        val ref = SyncScope.reference(sizes.keys)!!
        assertEquals(java.time.LocalDateTime.of(2026, 10, 17, 8, 0), ref)
        assertTrue(!SyncScope.isTooOld("re_261016_090000.mlg", 1, ref))
        assertTrue(SyncScope.isTooOld("re_261015_070000.mlg", 2, ref))
        assertTrue(!SyncScope.isTooOld("re_0042.mlg", 1, ref))
        val opts = SyncScope.options(sizes, ref)
        // Everything FIRST (the safe default), no "none" option.
        assertEquals(listOf(null, 1, 2, 7), opts.map { it.days })
        assertEquals(listOf(5, 3, 3, 4), opts.map { it.count })
        assertEquals(listOf("re_261017_080000.mlg", "re_261016_090000.mlg", "re_261015_070000.mlg",
                            "re_261001_080000.mlg", "re_0042.mlg"),
                     SyncScope.newestFirst(sizes.keys.toList()) { it })
    }

    @Test fun aWrongEcuClockCannotSkipTheNewestLog() {
        // ECU RTC stuck in 2019: against the phone clock every log looked years old.
        val sizes = mapOf("re_190301_100000.mlg" to 1L, "re_190302_100000.mlg" to 1L)
        val ref = SyncScope.reference(sizes.keys)!!
        for (o in SyncScope.options(sizes, ref)) assertTrue(o.count >= 1)
        assertTrue(!SyncScope.isTooOld("re_190302_100000.mlg", 1, ref))
        assertNull(SyncScope.reference(listOf("re_0001.mlg")))
    }

    @Test fun nonNumericScaleIsReportedNotDefaulted() {
        val m = IniModel.parse("""
            [TunerStudio]
            	defaultRuntimeRecordPerSec = 100;
            [OutputChannels]
            odd = scalar, U16, 4, "x", {1/fudge}, 0
            ok = scalar, U16, 6, "x", 0.5, -10
        """.trimIndent().reader().buffered())
        assertNull(m.channels["odd"])
        assertEquals(listOf("odd (non-numeric scale/translate)"), m.warnings)
        assertEquals(IniModel.Channel.Scalar("U16", 6, "x", 0.5, -10.0), m.channels["ok"])
        assertEquals(100, m.defaultHz)
    }

    @Test fun planDecodesSignedFloatBitsAndScale() {
        val ini = IniModel(
            signature = "s", blockSize = 32, blockingFactor = 1024,
            channels = mapOf(
                "neg" to IniModel.Channel.Scalar("S16", 0, "deg", 0.01, 0.0),
                "f" to IniModel.Channel.Scalar("F32", 2, "kPa", 1.0, 0.0),
                "u32" to IniModel.Channel.Scalar("U32", 6, "", 1.0, 0.0),
                "s08" to IniModel.Channel.Scalar("S08", 10, "C", 1.0, -40.0),
                "flag" to IniModel.Channel.Bits("U32", 12, 3, 3),
                "state" to IniModel.Channel.Bits("S32", 12, 4, 6),
                "wide" to IniModel.Channel.Bits("U32", 12, 0, 19),
                "beyond" to IniModel.Channel.Scalar("U32", 30, "", 1.0, 0.0)
            ),
            datalog = listOf("time", "neg", "f", "u32", "s08", "flag", "state", "wide",
                             "beyond", "expr").map { IniModel.LogEntry(it, it, 2) }
        )
        val plan = LivePlan.build(ini)
        assertEquals(listOf("beyond", "expr"), plan.skipped)
        assertEquals(listOf(MlgWriter.F32, MlgWriter.S16, MlgWriter.F32, MlgWriter.U32, MlgWriter.S08,
                            MlgWriter.U08, MlgWriter.U08, MlgWriter.U32),
                     plan.slots.map { it.field.type })
        val s08 = plan.slots[4].field
        assertEquals(-40f, s08.transform)                    // TS translate -> MLG transform
        assertEquals(0.01f, plan.slots[1].field.scale)

        // Block is little-endian, as the ECU sends it.
        val blk = ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        blk.putShort(0, (-1234).toShort())
        blk.putFloat(2, 101.5f)
        blk.putInt(6, 0xDEADBEEF.toInt())
        blk.put(10, (-5).toByte())
        blk.putInt(12, (0b101 shl 4) or (1 shl 3) or (1 shl 19) or (1 shl 31))
        val rec = ByteArray(plan.recordLength)
        plan.fill(blk.array(), rec, 1.25f)

        val r = ByteBuffer.wrap(rec)                         // record is big-endian
        assertEquals(1.25f, r.float, 0f)
        assertEquals((-1234).toShort(), r.short)
        assertEquals(101.5f, r.float, 0f)
        assertEquals(0xDEADBEEF.toInt(), r.int)
        assertEquals((-5).toByte(), r.get())
        assertEquals(1.toByte(), r.get())                    // bit 3
        assertEquals(0b101.toByte(), r.get())                // bits 4..6 of an S32 with bit 31 set
        assertEquals((1 shl 19) or (0b101 shl 4) or (1 shl 3), r.int)   // bits 0..19
    }

    @Test fun mlgFieldDescriptorMatchesSpecOffsets() {
        // Offsets from the MLVLG 2.0 spec as reproduced in rusEFI mlg_types.h - not from
        // MlgTrim, so this is not the writer checked against its own reader.
        val out = ByteArrayOutputStream()
        MlgWriter(out, listOf(MlgWriter.Field("Coolant", "deg C", MlgWriter.S16, 0.1f, -40f, 1)),
                  "i", 0L)
        val b = ByteBuffer.wrap(out.toByteArray())
        assertEquals(24 + 89, b.getInt(12))                  // info data start
        assertEquals(24 + 89 + 2, b.getInt(16))              // data begin (info "i\0")
        assertEquals(2, b.getShort(20).toInt())              // record length
        assertEquals(1, b.getShort(22).toInt())              // field count
        val f = 24
        assertEquals(MlgWriter.S16, b.get(f + 0).toInt())
        assertEquals("Coolant", String(out.toByteArray(), f + 1, 7))
        assertEquals("deg C", String(out.toByteArray(), f + 35, 5))
        assertEquals(0, b.get(f + 45).toInt())               // display style
        assertEquals(0.1f, b.getFloat(f + 46), 0f)           // scale
        assertEquals(-40f, b.getFloat(f + 50), 0f)           // transform
        assertEquals(1, b.get(f + 54).toInt())               // digits
    }
}
