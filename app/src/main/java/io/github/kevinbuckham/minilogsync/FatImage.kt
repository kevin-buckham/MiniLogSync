package io.github.kevinbuckham.minilogsync

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads files from the root directory of a small FAT12/FAT16 volume held in memory.
 *
 * WHY: rusEFI exposes its own .ini on a tiny built-in USB drive (mass-storage LUN 0),
 * and that drive is FAT12 - libaums only reads FAT32, which is why CardReader has
 * always skipped it. Measured on the owner's custom build: a 1088 KB FAT12 volume,
 * gzip-compressed in flash and inflated by the ECU as it is read, whose
 * rusefi_uaefi_pro.ini is byte-identical to the build's ini. Reading it means custom
 * firmware, whose ini rusefi.com does not publish, needs no file copying at all.
 *
 * Root directory only (that is where rusEFI puts its files), long file names decoded
 * for display, deleted entries, directories and the volume label skipped.
 */
object FatImage {

    data class Entry(val name: String, val size: Int, val firstCluster: Int)

    class Volume internal constructor(
        private val img: ByteArray,
        private val bytesPerSector: Int,
        private val sectorsPerCluster: Int,
        private val fatOffset: Int,
        private val dataOffset: Int,
        private val fat16: Boolean,
        val entries: List<Entry>
    ) {
        private val clusterBytes = bytesPerSector * sectorsPerCluster

        private fun next(cluster: Int): Int {
            return if (fat16) {
                u16(img, fatOffset + cluster * 2)
            } else {
                val o = fatOffset + cluster + cluster / 2
                val v = u16(img, o)
                if (cluster and 1 == 1) v ushr 4 else v and 0xFFF
            }
        }

        private val endOfChain: Int get() = if (fat16) 0xFFF8 else 0xFF8

        /** The file's bytes, following its cluster chain. Null if the chain is broken. */
        fun read(e: Entry): ByteArray? {
            val out = ByteArray(e.size)
            var have = 0
            var c = e.firstCluster
            var hops = 0
            val maxHops = img.size / clusterBytes + 1
            while (have < e.size) {
                if (c < 2 || c >= endOfChain || hops++ > maxHops) return null   // broken/looping
                val off = dataOffset + (c - 2) * clusterBytes
                if (off < 0 || off + clusterBytes > img.size) return null
                val n = minOf(clusterBytes, e.size - have)
                System.arraycopy(img, off, out, have, n)
                have += n
                c = next(c)
            }
            return out
        }
    }

    /** Null if [img] does not look like a FAT12/16 volume. */
    fun open(img: ByteArray): Volume? {
        if (img.size < 512) return null
        val b = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)
        val bps = b.getShort(11).toInt() and 0xFFFF
        val spc = b.get(13).toInt() and 0xFF
        val reserved = b.getShort(14).toInt() and 0xFFFF
        val fats = b.get(16).toInt() and 0xFF
        val rootEntries = b.getShort(17).toInt() and 0xFFFF
        val total16 = b.getShort(19).toInt() and 0xFFFF
        val spf = b.getShort(22).toInt() and 0xFFFF
        val total32 = b.getInt(32).toLong() and 0xFFFFFFFFL
        if (bps !in listOf(512, 1024, 2048, 4096) || spc == 0 || fats == 0 || spf == 0 ||
            rootEntries == 0) return null                    // FAT32 has rootEntries == 0

        val rootOffset = (reserved + fats * spf) * bps
        val rootBytes = rootEntries * 32
        val dataOffset = rootOffset + ((rootBytes + bps - 1) / bps) * bps
        if (dataOffset > img.size) return null
        val totalSectors = if (total16 != 0) total16.toLong() else total32
        val clusters = (totalSectors - dataOffset / bps) / spc
        val fat16 = clusters >= 4085                         // the FAT spec's own boundary

        val entries = ArrayList<Entry>()
        val lfn = ArrayList<String>()
        for (i in 0 until rootEntries) {
            val o = rootOffset + i * 32
            if (o + 32 > img.size) break
            val first = img[o].toInt() and 0xFF
            if (first == 0x00) break                         // end of directory
            if (first == 0xE5) { lfn.clear(); continue }     // deleted
            val attr = img[o + 11].toInt() and 0xFF
            if (attr == 0x0F) {                              // long-file-name piece, stored last-first
                lfn.add(0, lfnPart(img, o))
                continue
            }
            val longName = lfn.joinToString("")
            lfn.clear()
            if (attr and 0x18 != 0) continue                 // volume label or directory
            val short = shortName(img, o)
            entries += Entry(
                name = longName.ifEmpty { short },
                size = b.getInt(o + 28),
                firstCluster = u16(img, o + 26)
            )
        }
        return Volume(img, bps, spc, reserved * bps, dataOffset, fat16, entries)
    }

    private fun lfnPart(img: ByteArray, o: Int): String {
        val sb = StringBuilder()
        for (range in listOf(1 until 11, 14 until 26, 28 until 32)) {
            for (i in range.step(2)) {
                val ch = u16(img, o + i)
                if (ch == 0x0000 || ch == 0xFFFF) return sb.toString()
                sb.append(ch.toChar())
            }
        }
        return sb.toString()
    }

    private fun shortName(img: ByteArray, o: Int): String {
        val base = String(img, o, 8, Charsets.US_ASCII).trimEnd()
        val ext = String(img, o + 8, 3, Charsets.US_ASCII).trimEnd()
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}
