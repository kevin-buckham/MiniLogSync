package io.github.kevinbuckham.minilogsync

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * rusEFI / TunerStudio binary protocol framing.
 *
 * Verified against firmware source (tag 2026-08-21),
 * firmware/console/binary/tunerstudio_io.cpp -> writePacketHeader() / writeCrcPacketLarge():
 *
 *   [ length : uint16 big-endian ]   = payload length + 1
 *   [ code   : uint8            ]
 *   [ payload: N bytes          ]
 *   [ crc32  : uint32 big-endian]   over code + payload (NOT over the length field)
 *
 * Standard CRC-32 (IEEE 802.3 / zlib): rusEFI interoperates with TunerStudio,
 * which speaks that variant, so java.util.zip.CRC32 matches.
 */
object TsPacket {
    /** TS_EXECUTE - run a console command. firmware: #define TS_EXECUTE 'E' */
    private const val CODE_EXECUTE = 'E'.code.toByte()

    /** TS_RESPONSE_OK */
    const val RESPONSE_OK: Byte = 0

    /** Smallest possible reply: 2 length + 1 code + 4 crc. */
    const val MIN_REPLY = 7

    /**
     * Largest reply we accept: 2 length + the 1024-byte cap declaredFrameSize allows
     * + 4 crc. The read buffer must be at least this, or a long reply can never be
     * assembled and a command that DID execute is reported as unanswered.
     */
    const val MAX_FRAME = 2 + 1024 + 4

    fun execute(command: String): ByteArray =
        frame(CODE_EXECUTE, command.toByteArray(Charsets.US_ASCII))

    /** TS_HELLO_COMMAND: replies with the NUL-terminated firmware signature. */
    const val CODE_HELLO = 'S'.code.toByte()

    /**
     * TS_OUTPUT_COMMAND: read [count] bytes of the live output-channel block from
     * [offset]. Both are uint16 LITTLE-endian (the ini's ochGetCommand "O%2o%2c" with
     * endianness = little; firmware tunerstudio.cpp reads them as native uint16 on a
     * little-endian MCU). Reply payload: TS_RESPONSE_OK then the raw bytes.
     */
    const val CODE_OUTPUT = 'O'.code.toByte()

    fun outputRequest(offset: Int, count: Int): ByteArray = frame(
        CODE_OUTPUT,
        byteArrayOf(
            (offset and 0xFF).toByte(), (offset shr 8 and 0xFF).toByte(),
            (count and 0xFF).toByte(), (count shr 8 and 0xFF).toByte()
        )
    )

    /** One request in the msEnvelope_1.0 framing described above. */
    fun frame(code: Byte, payload: ByteArray): ByteArray {
        val body = ByteArray(1 + payload.size)
        body[0] = code
        payload.copyInto(body, 1)

        val crc = CRC32().apply { update(body) }.value.toInt()

        return ByteBuffer.allocate(2 + body.size + 4)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(body.size.toShort())
            .put(body)
            .putInt(crc)
            .array()
    }

    /**
     * How many bytes the frame starting at buf[0] claims to be, or -1 if the
     * length field itself is not yet available / implausible. Used so the reader
     * consumes the WHOLE frame instead of stopping at MIN_REPLY and leaving
     * trailing bytes to contaminate the next command.
     */
    /**
     * The raw declared payload length, or -1 if the length field is not yet available.
     * Unlike [declaredFrameSize] this does NOT reject implausible values, so callers
     * can tell "reply too large to verify" apart from "reply not arrived yet".
     */
    fun declaredLength(buf: ByteArray, len: Int): Int {
        if (len < 2) return -1
        return ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
    }

    fun declaredFrameSize(buf: ByteArray, len: Int): Int {
        if (len < 2) return -1
        val declared = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        if (declared < 1 || declared > 1024) return -1
        return 2 + declared + 4
    }

    /**
     * Parse a reply, VERIFYING the trailing CRC32.
     *
     * The CDC link also carries the firmware's asynchronous console output, and
     * stale bytes can linger from a previous command - so an unverified frame is
     * not trustworthy. Everything the app decides (above all "did logging
     * actually resume?") depends on this being right.
     */
    fun parseReply(buf: ByteArray, len: Int): Reply? {
        val frameSize = declaredFrameSize(buf, len)
        if (frameSize < 0 || len < frameSize) return null

        val bodyLen = frameSize - 6              // minus 2 length, minus 4 crc
        val body = buf.copyOfRange(2, 2 + bodyLen)

        val expected = CRC32().apply { update(body) }.value.toInt()
        val actual = ByteBuffer.wrap(buf, 2 + bodyLen, 4).order(ByteOrder.BIG_ENDIAN).int

        return Reply(code = body[0], crcOk = expected == actual)
    }

    data class Reply(val code: Byte, val crcOk: Boolean) {
        val isOk: Boolean get() = crcOk && code == RESPONSE_OK
    }

    fun hex(buf: ByteArray, len: Int): String =
        buf.copyOf(len).joinToString(" ") { "%02X".format(it) }
}
