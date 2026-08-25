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

    fun execute(command: String): ByteArray {
        val payload = command.toByteArray(Charsets.US_ASCII)
        val body = ByteArray(1 + payload.size)
        body[0] = CODE_EXECUTE
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
