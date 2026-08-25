package io.github.kevinbuckham.minilogsync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/**
 * The whole one-button sync: mount -> read card -> copy new files -> restore.
 *
 * Ordering matters. libaums skips LUNs with no medium, so the card must be
 * attached (`sdmode pc`) BEFORE the storage layer is initialised.
 *
 * Two properties this class must never violate:
 *  1. The caller learns whether ECU logging was ACTUALLY restored - never assume it.
 *  2. A file is recorded as synced only once its bytes have been read back from
 *     the destination and verified. History is permanent; a false positive means
 *     that log is never copied again.
 */
class SyncJob(
    private val context: Context,
    private val link: EcuLink,
    private val history: SyncHistory
) {

    /** Set from the UI thread to stop after the current chunk. */
    val cancelled = AtomicBoolean(false)

    fun cancel() = cancelled.set(true)

    private fun active() = !cancelled.get()

    /**
     * @param restored whether the ECU is confirmed to have its card back.
     *        If false, the ECU may still be mounted and NOT LOGGING.
     */
    data class Outcome(
        val copied: Int,
        val skipped: Int,
        val failed: Int,
        val cancelled: Boolean,
        val restored: Boolean
    ) {
        val clean: Boolean get() = failed == 0 && !cancelled && restored
    }

    fun run(destTree: Uri, log: (String) -> Unit): Outcome {
        var copied = 0
        var skipped = 0
        var failed = 0

        val dest = DocumentFile.fromTreeUri(context, destTree)
        if (dest == null || !dest.canWrite()) {
            log("Destination folder is not writable - pick it again")
            return Outcome(0, 0, 0, cancelled.get(), restored = true)  // never mounted
        }

        val mount = link.sendCommand(EcuLink.CMD_MOUNT_PHONE)
        if (!mount.ok) {
            log("Mount failed: ${mount.message}")
            return Outcome(0, 0, 0, cancelled.get(), restored = true)  // never mounted
        }
        log("Card mounted; ECU is not logging")

        val card = CardReader(context)
        try {
            val err = openCardWithRetry(card, log)
            if (err != null) {
                log(err)
            } else {
                val files = card.listFiles().sortedBy { it.name }
                log("Card has ${files.size} files")

                for (f in files) {
                    if (!active()) {
                        log("Cancelled - $copied file(s) saved; the rest will copy next time")
                        break
                    }
                    when (copyOne(card, f, dest, log)) {
                        FileResult.COPIED -> copied++
                        FileResult.SKIPPED -> skipped++
                        FileResult.FAILED -> failed++
                        FileResult.CANCELLED -> {
                            log("Cancelled during ${f.name} - $copied saved, this one retries next time")
                        }
                    }
                    if (!active()) break
                }

                if (active()) log("Done: $copied new, $skipped already had, $failed failed")
            }
        } catch (e: Exception) {
            log("Sync error: ${e.message}")
        } finally {
            card.close()
        }

        // ALWAYS give the card back, and report honestly whether it worked.
        val restored = restoreLogging(log)
        return Outcome(copied, skipped, failed, cancelled.get(), restored)
    }

    /**
     * The LUN may take a moment to attach, and this ECU drops commands often
     * enough that a single probe is not good enough.
     */
    private fun openCardWithRetry(card: CardReader, log: (String) -> Unit): String? {
        var lastError: String? = null
        for (attempt in 1..5) {
            if (!active()) return "Cancelled before the card was opened"
            Thread.sleep(if (attempt == 1) 1200 else 900)
            lastError = card.open(log)
            if (lastError == null) return null
            log("Card not ready (attempt $attempt): $lastError")
        }
        return lastError
    }

    private enum class FileResult { COPIED, SKIPPED, FAILED, CANCELLED }

    private fun copyOne(
        card: CardReader,
        f: me.jahnen.libaums.core.fs.UsbFile,
        dest: DocumentFile,
        log: (String) -> Unit
    ): FileResult {
        val name = f.name
        val size = f.length

        if (history.isCopied(name, size)) return FileResult.SKIPPED

        // Guard the WHOLE per-file operation: a provider that throws on rename
        // must not abort the remaining files.
        return try {
            val tmpName = "$name.part"
            dest.findFile(tmpName)?.delete()
            val tmp = dest.createFile("application/octet-stream", tmpName)
                ?: run { log("Could not create $tmpName"); return FileResult.FAILED }

            log("Copying $name (${size / 1024} kB)...")

            val sourceCrc = CRC32()
            val written = context.contentResolver.openOutputStream(tmp.uri)!!.use { os ->
                card.copyTo(f, os, ::active) { chunk, len -> sourceCrc.update(chunk, 0, len) }
            }

            if (!active()) { tmp.delete(); return FileResult.CANCELLED }

            if (written != size) {
                log("  SIZE MISMATCH $name: got $written of $size - not recording")
                tmp.delete()
                return FileResult.FAILED
            }

            // BLOCKER fix: closing a SAF stream does not mean the bytes are
            // durable - cloud providers buffer and can fail the upload later.
            // Read the destination back and verify before trusting it.
            val readBack = verifyDestination(tmp, size, sourceCrc.value)
            if (readBack != null) {
                log("  VERIFY FAILED $name: $readBack - not recording")
                tmp.delete()
                return FileResult.FAILED
            }

            dest.findFile(name)?.delete()
            if (!tmp.renameTo(name)) {
                log("  Could not rename $tmpName - not recording")
                return FileResult.FAILED
            }

            history.markCopied(name, size)
            FileResult.COPIED
        } catch (e: Exception) {
            log("  FAILED $name: ${e.message}")
            runCatching { dest.findFile("$name.part")?.delete() }
            FileResult.FAILED
        }
    }

    /** Re-reads the just-written file. Returns null if good, else a reason. */
    private fun verifyDestination(file: DocumentFile, expectSize: Long, expectCrc: Long): String? =
        try {
            val crc = CRC32()
            var total = 0L
            context.contentResolver.openInputStream(file.uri)!!.use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    crc.update(buf, 0, n)
                    total += n
                }
            }
            when {
                total != expectSize -> "read back $total of $expectSize bytes"
                crc.value != expectCrc -> "checksum mismatch on read-back"
                else -> null
            }
        } catch (e: Exception) {
            "could not read back (${e.message})"
        }

    /** Hands the card back, retrying; returns true only if the ECU confirmed it. */
    private fun restoreLogging(log: (String) -> Unit): Boolean {
        val r = link.sendCommand(EcuLink.CMD_RESTORE_AUTO, attempts = 4)
        if (r.ok) {
            log("Card returned to ECU - logging resumed")
            return true
        }
        val forced = link.sendCommand(EcuLink.CMD_RESTORE_ECU, attempts = 2)
        if (forced.ok) {
            log("Card returned to ECU (forced logging mode)")
            return true
        }
        log("*** WARNING: COULD NOT RESTORE ECU LOGGING (${r.message}) ***")
        log("*** The ECU may still be mounted and NOT logging. Reconnect and tap 'Force ECU logging'. ***")
        return false
    }
}
