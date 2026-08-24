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
 * The CRC is standard CRC-32 (IEEE 802.3 / zlib): rusEFI must interoperate with
 * TunerStudio itself, which speaks that variant, so java.util.zip.CRC32 matches.
 */
object TsPacket {
    /** TS_EXECUTE - run a console command. firmware rusefi_generated_*.h: #define TS_EXECUTE 'E' */
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
            .putShort(body.size.toShort())   // length = code + payload
            .put(body)
            .putInt(crc)
            .array()
    }

    /**
     * Pull the response code out of a reply frame.
     * Returns null if the buffer is too short to contain a complete frame.
     */
    fun responseCode(buf: ByteArray, len: Int): Byte? {
        if (len < MIN_REPLY) return null
        val declared = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        if (declared < 1 || len < 2 + declared + 4) return null
        return buf[2]
    }

    fun hex(buf: ByteArray, len: Int): String =
        buf.copyOf(len).joinToString(" ") { "%02X".format(it) }
}
