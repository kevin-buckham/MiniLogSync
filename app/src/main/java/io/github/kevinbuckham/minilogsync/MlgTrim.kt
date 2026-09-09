package io.github.kevinbuckham.minilogsync

/**
 * Understands just enough of the rusEFI MLG (MLVLG v2) container to stop copying
 * at the end of the real data.
 *
 * WHY: the ECU pre-allocates every log to 32 MB (f_expand, so the FAT survives a
 * power cut) and only calls f_truncate on a graceful unmount - which never happens
 * at key-off. So a 30-second log and a 40-minute log are both 32 MB on the card,
 * and most of that is padding we would otherwise drag over a slow, flaky USB link.
 *
 * This is a port of extracted/truncate_log.py from the MiniRusEFI project, which
 * has been used on this car's logs for months. Same integrity rules, deliberately:
 *   - block type byte must be 0 (data); 1 is a marker block (54 bytes, passed through)
 *   - the rolling block counter (byte 1) must be previous + 1 (mod 256)
 *   - an all-zero block header means pre-allocation padding -> stop
 * Stopping on a BLOCK BOUNDARY is what keeps this safe: a naive byte-level cut
 * would leave a half record and break log viewers.
 *
 * Deliberately NOT used: "looks like zeros" heuristics. Real MLG records are more
 * than half zero bytes, so scanning for zero runs is unsafe.
 *
 * Stride comes from the header's rec_len field (+5), which was verified equal to
 * the sum of all field widths on this car's logs.
 */
object MlgTrim {

    data class Header(val dataBegin: Int, val stride: Int)

    const val PROBE_BYTES = 32

    /** Parse the fixed part of the header. Returns null if this is not an MLG. */
    fun parseHeader(b: ByteArray, len: Int): Header? {
        if (len < 24) return null
        if (b[0] != 'M'.code.toByte() || b[1] != 'L'.code.toByte() ||
            b[2] != 'V'.code.toByte() || b[3] != 'L'.code.toByte() ||
            b[4] != 'G'.code.toByte()
        ) return null

        val version = be16(b, 6)
        // 6..7 version, 8..11 timestamp, then infoStart (int32 on v2, int16 on v1)
        val afterInfo = if (version == 2) 16 else 14
        val dataBegin = be32(b, afterInfo)
        val recLen = be16(b, afterInfo + 4)

        if (dataBegin <= 0 || recLen <= 0 || recLen > 65000) return null
        return Header(dataBegin, recLen + 5)
    }

    /**
     * The MLG header's own timestamp (uint32 BE at offset 8), used as a CONTENT stamp
     * so sync history cannot confuse two different logs that happen to share a name.
     * Returns 0 when this is not an MLG or the read was short.
     */
    fun timestampOf(b: ByteArray, len: Int): Long {
        if (len < 12) return 0L
        if (b[0] != 'M'.code.toByte() || b[1] != 'L'.code.toByte() ||
            b[2] != 'V'.code.toByte() || b[3] != 'L'.code.toByte() ||
            b[4] != 'G'.code.toByte()
        ) return 0L
        return be32(b, 8).toLong() and 0xFFFFFFFFL
    }

    /** A marker block is a fixed 54 bytes rather than one record stride. */
    const val MARKER_SIZE = 54

    enum class Verdict { DATA, MARKER, STOP }

    /**
     * Classify the block starting at [off].
     * [prevCounter] is null for the first block.
     */
    fun classify(b: ByteArray, off: Int, prevCounter: Int?): Verdict {
        return when {
            b[off] == 1.toByte() -> Verdict.MARKER
            b[off] != 0.toByte() -> Verdict.STOP           // unknown block type
            // all-zero header = pre-allocation padding (also covers the
            // counter-rolls-into-zeros edge that would otherwise look continuous)
            b[off + 1] == 0.toByte() && b[off + 2] == 0.toByte() &&
                b[off + 3] == 0.toByte() -> Verdict.STOP
            prevCounter != null &&
                (b[off + 1].toInt() and 0xFF) != ((prevCounter + 1) and 0xFF) -> Verdict.STOP
            else -> Verdict.DATA
        }
    }

    fun counterOf(b: ByteArray, off: Int): Int = b[off + 1].toInt() and 0xFF

    private fun be16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}
