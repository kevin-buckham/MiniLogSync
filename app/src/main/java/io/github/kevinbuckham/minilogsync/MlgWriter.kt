package io.github.kevinbuckham.minilogsync

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes an MLVLG v2 (.mlg) log - the format rusEFI's SD card logs use and
 * MegaLogViewer/TunerStudio read. Written from the format spec (EFI Analytics
 * "MLG_Binary_LogFormat_2.0", reproduced in rusEFI firmware mlg_types.h); all
 * multi-byte values are BIG-endian.
 *
 *   header (24):  "MLVLG\0", version u16 = 2, unix time u32, info start u32,
 *                 data begin u32, record length u16, field count u16
 *   field (89):   type u8, name[34], units[10], display style u8, scale f32,
 *                 transform f32, digits s8, category[34]
 *   info:         NUL-terminated text
 *   record:       block type 0, counter u8, timestamp u16 (10 us units, wraps),
 *                 field data, checksum u8 = sum of the field data bytes
 *
 * The record framing is the same one MlgTrim parses (stride = record length + 5).
 */
class MlgWriter(
    private val out: OutputStream,
    private val fields: List<Field>,
    info: String,
    unixTime: Long
) {
    /** Displayed value = (raw + transform) * scale. */
    data class Field(
        val name: String, val units: String, val type: Int,
        val scale: Float, val transform: Float, val digits: Int
    )

    val recordLength: Int = fields.sumOf { sizeOf(it.type) }
    private var counter = 0
    private val block = ByteArray(4 + recordLength + 1)

    init {
        val infoBytes = (info + "\u0000").toByteArray(Charsets.US_ASCII)
        val infoStart = HEADER + FIELD * fields.size
        val h = ByteBuffer.allocate(infoStart).order(ByteOrder.BIG_ENDIAN)
        h.put("MLVLG".toByteArray(Charsets.US_ASCII)).put(0)
        h.putShort(2)
        h.putInt(unixTime.toInt())
        h.putInt(infoStart)
        h.putInt(infoStart + infoBytes.size)
        h.putShort(recordLength.toShort())
        h.putShort(fields.size.toShort())
        for (f in fields) {
            h.put(f.type.toByte())
            putText(h, f.name, 34)
            putText(h, f.units, 10)
            h.put(0)                                   // display style: float
            h.putFloat(f.scale)
            h.putFloat(f.transform)
            h.put(f.digits.coerceIn(0, 9).toByte())
            putText(h, "", 34)
        }
        check(h.position() == infoStart)
        out.write(h.array())
        out.write(infoBytes)
    }

    /** [data] must hold exactly [recordLength] big-endian bytes, in field order. */
    fun writeRecord(data: ByteArray, elapsedMicros: Long) {
        val ts = (elapsedMicros / 10) and 0xFFFF
        block[0] = 0
        block[1] = counter.toByte()
        block[2] = (ts shr 8).toByte()
        block[3] = ts.toByte()
        data.copyInto(block, 4, 0, recordLength)
        var sum = 0
        for (i in 0 until recordLength) sum += data[i]
        block[4 + recordLength] = sum.toByte()
        out.write(block)
        counter = (counter + 1) and 0xFF
    }

    companion object {
        const val HEADER = 24
        const val FIELD = 89

        const val U08 = 0; const val S08 = 1; const val U16 = 2; const val S16 = 3
        const val U32 = 4; const val S32 = 5; const val F32 = 7

        fun sizeOf(type: Int): Int = when (type) {
            U08, S08 -> 1
            U16, S16 -> 2
            U32, S32, F32 -> 4
            else -> 8
        }

        /** TunerStudio type names, as used in the ini. */
        fun typeOf(ini: String): Int? = when (ini) {
            "U08" -> U08; "S08" -> S08; "U16" -> U16; "S16" -> S16
            "U32" -> U32; "S32" -> S32; "F32" -> F32
            else -> null
        }

        /** Fixed-width, NUL-padded, always NUL-terminated. */
        private fun putText(b: ByteBuffer, s: String, width: Int) {
            val bytes = s.toByteArray(Charsets.US_ASCII)
            val n = minOf(bytes.size, width - 1)
            b.put(bytes, 0, n)
            repeat(width - n) { b.put(0) }
        }
    }
}
