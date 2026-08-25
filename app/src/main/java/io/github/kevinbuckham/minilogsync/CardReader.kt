package io.github.kevinbuckham.minilogsync

import android.content.Context
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileInputStream
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

    private var device: UsbMassStorageDevice? = null
    private var root: UsbFile? = null

    /** Opens the card. Returns null on success, or a human-readable error. */
    fun open(log: (String) -> Unit = {}): String? {
        close()

        val devices = UsbMassStorageDevice.getMassStorageDevices(context)
        if (devices.isEmpty()) return "No mass-storage interface found on the ECU"

        for (dev in devices) {
            try {
                dev.init()
            } catch (e: Exception) {
                // Do NOT swallow this silently: if probing the ECU's USB stack
                // ever upsets it, this message is the only trail we would have.
                log("  storage init failed: ${e.message}")
                continue
            }

            // Pick the volume that looks like the log card rather than the INI
            // ramdisk: the card is the one holding rusEFI log/report files.
            for (partition in dev.partitions) {
                val fs = partition.fileSystem ?: continue
                val r = fs.rootDirectory ?: continue
                val names = runCatching { r.list().toList() }.getOrDefault(emptyList())
                val looksLikeCard = names.any {
                    it.startsWith("re_") || it.endsWith(".mlg") ||
                        it.contains("fail_") || it == "index.txt"
                }
                if (looksLikeCard) {
                    device = dev
                    root = r
                    return null
                }
            }
            dev.close()
        }
        return "Found storage, but no volume containing rusEFI logs " +
            "(is the card mounted? tap Mount first)"
    }

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

    fun close() {
        runCatching { device?.close() }
        device = null
        root = null
    }
}
