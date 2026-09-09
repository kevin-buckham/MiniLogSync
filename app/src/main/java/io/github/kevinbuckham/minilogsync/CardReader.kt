package io.github.kevinbuckham.minilogsync

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileInputStream
import me.jahnen.libaums.core.partition.Partition
import me.jahnen.libaums.core.partition.PartitionTableFactory
import me.jahnen.libaums.core.usb.UsbCommunication
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import java.io.OutputStream

/**
 * Reads the ECU's SD card over USB mass storage, in userspace.
 *
 * Android will not mount the card itself: the ECU exposes multiple LUNs
 * (LUN 0 = the INI ramdisk, LUN 1 = the SD card) and Android only handles the
 * first. libaums issues GET_MAX_LUN, walks every LUN and skips the ones with no
 * medium - so this must be initialised AFTER `sdmode pc`, once the card is
 * actually attached.
 *
 * Read-only by design (spec section 8): nothing here writes to or deletes from the card.
 */
class CardReader(private val context: Context) {

    private companion object {
        /**
         * Anything at least this big is the SD card, not the INI ramdisk.
         * Measured on this ECU: ramdisk 1 MB, card 60905 MB.
         */
        const val MIN_CARD_MB = 100L
    }

    private var comm: UsbCommunication? = null
    private var root: UsbFile? = null

    /** Opens the card. Returns null on success, or a human-readable error. */
    fun open(log: (String) -> Unit = {}): String? {
        close()

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        // We deliberately do NOT use UsbMassStorageDevice.init(): it walks the
        // LUNs but only tolerates "no media" per LUN - an unreadable LUN throws
        // UnsupportedPartitionTableException straight out of the enumeration.
        // This ECU exposes LUN 0 as a small INI ramdisk that libaums cannot
        // parse, which aborted the walk before ever reaching the SD card on
        // LUN 1. So enumerate the LUNs ourselves and skip the bad ones.
        for (dev in usbManager.deviceList.values) {
            if (!usbManager.hasPermission(dev)) continue

            val (iface, inEp, outEp) = findMassStorage(dev) ?: continue

            val c = try {
                UsbCommunicationFactory.createUsbCommunication(usbManager, dev, iface, outEp, inEp)
            } catch (e: Exception) {
                log("  cannot open mass storage: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")
                continue
            }

            var keepComm = false
            val maxLunBuf = ByteArray(1)
            val maxLun = try {
                c.controlTransfer(161, 254, 0, iface.id, maxLunBuf, 1)
                maxLunBuf[0].toInt() and 0xFF
            } catch (e: Exception) {
                log("  GET_MAX_LUN failed (${e.javaClass.simpleName}), assuming 1 LUN")
                0
            }
            log("  device exposes ${maxLun + 1} LUN(s)")

            for (lun in 0..maxLun) {
                try {
                    val block = BlockDeviceDriverFactory.createBlockDevice(c, lun = lun.toByte())
                    block.init()
                    dumpLun(lun, block, log)
                    val table = PartitionTableFactory.createPartitionTable(block)
                    if (table.partitionTableEntries.isEmpty()) {
                        log("  LUN $lun: partition table has no entries")
                    }
                    for (entry in table.partitionTableEntries) {
                        val partition = Partition.createPartition(entry, block) ?: continue
                        val fs = partition.fileSystem ?: continue
                        val r = fs.rootDirectory ?: continue
                        val names = runCatching { r.list().toList() }.getOrDefault(emptyList())
                        val label = runCatching { fs.volumeLabel }.getOrNull()?.trim().orEmpty()
                        val capacityMb = block.blocks * block.blockSize / (1024L * 1024L)

                        // Identify by CAPACITY first, not by contents. The INI ramdisk is
                        // ~1 MB and the SD card is tens of GB, so size separates them
                        // reliably - and unlike a filename test it still works on a
                        // freshly formatted card, which is empty by definition.
                        val hasLogs = names.any {
                            it.startsWith("re_") || it.endsWith(".mlg") ||
                                it.contains("fail_") || it == "index.txt"
                        }
                        val bigEnough = capacityMb >= MIN_CARD_MB
                        val looksLikeCard = bigEnough || hasLogs

                        log("  LUN $lun: ${names.size} entries, ${capacityMb} MB" +
                            (if (label.isNotEmpty()) ", label '$label'" else "") +
                            when {
                                looksLikeCard && !hasLogs -> "  <- log card (empty - newly formatted?)"
                                looksLikeCard -> "  <- rusEFI log card"
                                else -> "  (too small to be the card - this is the INI ramdisk)"
                            })
                        if (looksLikeCard) {
                            comm = c
                            root = r
                            keepComm = true
                            return null
                        }
                    }
                } catch (e: Exception) {
                    // Expected for LUN 0 (the INI ramdisk) and for empty slots -
                    // but log enough to tell an expected skip from a real defect.
                    val where = e.stackTrace.firstOrNull { it.className.contains("libaums") }
                        ?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}" } ?: ""
                    log("  LUN $lun skipped: ${e.javaClass.simpleName}: " +
                        "${e.message ?: "(no message)"}$where")
                }
            }
            if (!keepComm) runCatching { c.close() }
        }

        return "No volume containing rusEFI logs found (is the card mounted? tap Mount first)"
    }

    /**
     * Logs what a LUN actually contains.
     *
     * libaums can only read FAT32 - its FileSystemFactory registers
     * Fat32FileSystemCreator and nothing else - so when a LUN is rejected we
     * have to know whether it is exFAT/FAT16 (not fixable in this app at all)
     * or a FAT32 volume we are mis-parsing. Guessing has cost several rounds
     * already; this prints the bytes.
     */
    private fun dumpLun(
        lun: Int,
        block: me.jahnen.libaums.core.driver.BlockDeviceDriver,
        log: (String) -> Unit
    ) {
        try {
            val bs = block.blockSize
            val mb = block.blocks * bs / (1024L * 1024L)
            log("  LUN $lun: blockSize=$bs blocks=${block.blocks} (~$mb MB)")

            val buf = java.nio.ByteBuffer.allocate(maxOf(512, bs))
            block.read(0, buf)
            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)

            fun ascii(off: Int, len: Int) = (off until off + len)
                .joinToString("") {
                    val v = buf.get(it).toInt() and 0xFF
                    if (v in 32..126) v.toChar().toString() else "."
                }
            fun hex(off: Int, len: Int) =
                (off until off + len).joinToString(" ") { "%02X".format(buf.get(it)) }

            log("    s0[0..15] ${hex(0, 16)}")
            log("    oem='${ascii(3, 8)}' fat16id='${ascii(0x36, 8)}' fat32id='${ascii(0x52, 8)}'")
            log("    sig=${hex(510, 2)}")
            for (i in 0..3) {
                val o = 446 + i * 16
                val ty = buf.get(o + 4).toInt() and 0xFF
                if (ty == 0) continue
                log("    mbr[$i] type=0x%02X lba=%d count=%d"
                    .format(ty, buf.getInt(o + 8), buf.getInt(o + 12)))
            }
        } catch (e: Exception) {
            log("  LUN $lun: sector 0 unreadable: " +
                "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")
        }
    }

    private data class Msc(val iface: UsbInterface, val inEp: UsbEndpoint, val outEp: UsbEndpoint)

    /** Bulk-only SCSI mass storage: class 8, subclass 6, protocol 80. */
    private fun findMassStorage(dev: android.hardware.usb.UsbDevice): Msc? {
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE) continue
            if (iface.interfaceSubclass != 6 || iface.interfaceProtocol != 80) continue

            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp != null && outEp != null) return Msc(iface, inEp, outEp)
        }
        return null
    }

    /**
     * Reads just the MLG header and returns its timestamp, for sync-history keying.
     * 32 bytes per file - negligible next to a multi-megabyte copy - and it is what
     * stops a renamed/renumbered log being mistaken for one already copied.
     */
    fun headerStamp(file: UsbFile): Long = runCatching {
        UsbFileInputStream(file).use { s ->
            val probe = ByteArray(MlgTrim.PROBE_BYTES)
            val n = readFully(s, probe, probe.size)
            MlgTrim.timestampOf(probe, n)
        }
    }.getOrDefault(0L)

    /** Files in the card root, excluding directories. */
    fun listFiles(): List<UsbFile> =
        root?.listFiles()?.filter { !it.isDirectory } ?: emptyList()

    /**
     * Streams one file out. Returns bytes copied.
     * [keepGoing] is polled every chunk so a cancel takes effect mid-file
     * instead of waiting for a 32 MB log to finish.
     */
    fun copyTo(
        file: UsbFile,
        out: OutputStream,
        keepGoing: () -> Boolean,
        onChunk: (ByteArray, Int) -> Unit
    ): Long {
        val buf = ByteArray(64 * 1024)
        var copied = 0L
        UsbFileInputStream(file).use { stream ->
            while (keepGoing()) {
                // This ECU drops roughly one mass-storage command in three, so a
                // single failed read must not be mistaken for end-of-file.
                var n = -1
                var attempt = 0
                while (attempt < 5) {
                    n = try {
                        stream.read(buf)
                    } catch (e: Exception) {
                        if (attempt == 4) throw e
                        -1
                    }
                    if (n >= 0) break
                    attempt++
                    Thread.sleep(50L * attempt)
                }
                if (n <= 0) break
                out.write(buf, 0, n)
                onChunk(buf, n)
                copied += n
            }
        }
        out.flush()
        return copied
    }

    /**
     * Copy an .mlg, stopping at the end of the real data instead of dragging the
     * 32 MB of pre-allocation padding across a slow link.
     *
     * Falls back to a plain full copy whenever anything is unexpected (not an
     * MLG, odd header, short read) - the padding is only wasted time, whereas
     * dropping real data would be a silent loss.
     *
     * @return Pair(bytesWritten, recordsKept) - recordsKept is -1 if not trimmed.
     */
    fun copyTrimmed(
        file: UsbFile,
        out: OutputStream,
        keepGoing: () -> Boolean,
        onChunk: (ByteArray, Int) -> Unit
    ): Pair<Long, Int> {
        UsbFileInputStream(file).use { stream ->
            // --- header ---
            val probe = ByteArray(MlgTrim.PROBE_BYTES)
            val got = readFully(stream, probe, probe.size)
            if (got < probe.size) {
                emit(out, probe, got, onChunk)
                return Pair(got.toLong(), -1)
            }
            val header = MlgTrim.parseHeader(probe, got)
                ?: run {   // not an MLG: copy the rest verbatim
                    emit(out, probe, got, onChunk)
                    return Pair(got + drainRest(stream, out, keepGoing, onChunk), -1)
                }

            emit(out, probe, got, onChunk)
            var written = got.toLong()

            // rest of the header block, verbatim
            var remainingHeader = header.dataBegin - got
            val hbuf = ByteArray(64 * 1024)
            while (remainingHeader > 0) {
                if (!keepGoing()) return Pair(written, -1)
                val want = minOf(remainingHeader, hbuf.size)
                val n = readFully(stream, hbuf, want)
                if (n <= 0) return Pair(written, -1)
                emit(out, hbuf, n, onChunk)
                written += n
                remainingHeader -= n
            }

            // --- data blocks ---
            var pending = ByteArray(0)
            var prevCounter: Int? = null
            var records = 0
            val chunk = ByteArray(256 * 1024)

            while (keepGoing()) {
                val n = readFully(stream, chunk, chunk.size)
                val buf = if (pending.isEmpty() && n == chunk.size) chunk
                          else pending + chunk.copyOf(maxOf(n, 0))
                val avail = if (pending.isEmpty() && n == chunk.size) n else pending.size + maxOf(n, 0)

                var off = 0
                var stop = false
                while (off + header.stride <= avail) {
                    when (MlgTrim.classify(buf, off, prevCounter)) {
                        MlgTrim.Verdict.MARKER -> {
                            if (off + MlgTrim.MARKER_SIZE > avail) break
                            off += MlgTrim.MARKER_SIZE
                        }
                        MlgTrim.Verdict.STOP -> { stop = true; break }
                        MlgTrim.Verdict.DATA -> {
                            prevCounter = MlgTrim.counterOf(buf, off)
                            records++
                            off += header.stride
                        }
                    }
                }

                if (off > 0) { emit(out, buf, off, onChunk); written += off }
                if (stop) { out.flush(); return Pair(written, records) }
                if (n <= 0) break                      // end of file
                pending = buf.copyOfRange(off, avail)  // partial block carried over
            }

            out.flush()
            return Pair(written, records)
        }
    }

    private fun emit(out: OutputStream, b: ByteArray, len: Int, onChunk: (ByteArray, Int) -> Unit) {
        if (len <= 0) return
        out.write(b, 0, len)
        onChunk(b, len)
    }

    /**
     * Reads until [want] bytes or EOF.
     *
     * Retries only genuine EXCEPTIONS. A read returning -1 is the InputStream EOF
     * contract, not a transient fault: retrying it five times with sleeps burned time
     * at every legitimate end-of-file, and - worse - conflating the two meant a failed
     * read could be skipped over, silently dropping a chunk. A dropped chunk still
     * passes the CRC check (the source CRC is computed from what we streamed) and, on
     * a trimmed log, reads as a legitimate end-of-data.
     */
    private fun readFully(stream: java.io.InputStream, buf: ByteArray, want: Int): Int {
        var total = 0
        while (total < want) {
            var n = -1
            var attempt = 0
            while (attempt < 5) {
                try {
                    n = stream.read(buf, total, want - total)
                    break                       // includes n == -1: real EOF, stop
                } catch (e: Exception) {
                    if (attempt == 4) throw e    // never swallow: skipping bytes corrupts
                    attempt++
                    Thread.sleep(50L * attempt)
                }
            }
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun drainRest(
        stream: java.io.InputStream,
        out: OutputStream,
        keepGoing: () -> Boolean,
        onChunk: (ByteArray, Int) -> Unit
    ): Long {
        val buf = ByteArray(64 * 1024)
        var copied = 0L
        while (keepGoing()) {
            val n = readFully(stream, buf, buf.size)
            if (n <= 0) break
            emit(out, buf, n, onChunk)
            copied += n
        }
        out.flush()
        return copied
    }

    fun close() {
        runCatching { comm?.close() }
        comm = null
        root = null
    }
}
