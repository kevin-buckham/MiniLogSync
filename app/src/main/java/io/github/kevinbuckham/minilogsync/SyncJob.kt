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

    /** Structured so the UI can drive a real progress bar, not just print a line. */
    data class Progress(
        val fileIndex: Int,
        val fileCount: Int,
        val fileName: String,
        val overallPercent: Int,
        val detail: String
    )

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

    /**
     * @param onSafeToUnplug fired once the ECU has its card back and the USB link is
     *        no longer needed - the owner can leave while verification finishes.
     */
    fun run(
        destTree: Uri,
        log: (String) -> Unit,
        progress: (Progress?) -> Unit = {},
        onSafeToUnplug: () -> Unit = {}
    ): Outcome {
        var copied = 0
        var skipped = 0
        var failed = 0
        val pending = mutableListOf<Pending>()

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

        // Hand the USB device over to the storage layer: Android will not give
        // libaums a connection while our CDC link holds one.
        link.releaseForStorage()
        log("Serial link released for storage access")

        val card = CardReader(context)
        try {
            val err = openCardWithRetry(card, log)
            if (err != null) {
                log(err)
            } else {
                val files = card.listFiles().sortedBy { it.name }
                log("Card has ${files.size} files")

                val todo = files.filter { !history.isCopied(it.name, it.length) }
                val totalBytes = todo.sumOf { it.length }
                log("${todo.size} new file(s), ${fmtMb(totalBytes)} to copy")
                var doneBytes = 0L
                var index = 0

                for (f in files) {
                    if (!active()) {
                        log("Cancelled - $copied file(s) saved; the rest will copy next time")
                        break
                    }
                    val isNew = !history.isCopied(f.name, f.length)
                    if (isNew) index++
                    when (copyOne(card, f, dest, log, index, todo.size, doneBytes, totalBytes,
                                  progress, pending)) {
                        FileResult.COPIED -> doneBytes += f.length
                        FileResult.SKIPPED -> skipped++
                        FileResult.FAILED -> failed++
                        FileResult.CANCELLED -> {
                            log("Cancelled during ${f.name} - ${pending.size} saved, this one retries next time")
                        }
                    }
                    if (!active()) break
                }

                progress(null)
                if (active()) log("Copied ${pending.size} file(s) off the card")
            }
        } catch (e: Exception) {
            log("Sync error: ${e.message}")
        } finally {
            card.close()
        }

        // Re-acquire the serial link so we can hand the card back.
        val reopened = link.reopen()
        if (!link.isOpen) log("WARNING: $reopened")

        // ALWAYS give the card back, and report honestly whether it worked.
        // This happens BEFORE verification on purpose: verification reads the
        // DESTINATION, not the card, so holding the ECU un-mounted through it kept
        // the engine from logging for no reason - roughly 40% of the total run.
        val restored = restoreLogging(log)
        if (restored) {
            log("*** SAFE TO UNPLUG - the ECU has its card back and is logging ***")
            if (pending.isNotEmpty()) {
                log("Verifying ${pending.size} file(s) on the destination; the ECU is no longer needed.")
            }
            onSafeToUnplug()
        }

        // Verification phase - destination only. If the app is killed here, nothing is
        // corrupted: unverified files simply are not recorded, so they copy again next time.
        for ((i, p) in pending.withIndex()) {
            progress(Progress(i + 1, pending.size, p.name, 100,
                "verifying ${fmtMb(p.written)} on the destination…"))
            when (finalize(p, dest, log)) {
                true -> copied++
                false -> failed++
            }
        }
        progress(null)
        log("Done: $copied new, $skipped already had, $failed failed")

        return Outcome(copied, skipped, failed, cancelled.get(), restored)
    }

    /** A file copied off the card, not yet verified or recorded. */
    private data class Pending(
        val tmp: DocumentFile,
        val name: String,
        val cardSize: Long,
        val written: Long,
        val crc: Long
    )

    /**
     * Verify one copied file against the source bytes, then publish and record it.
     * Runs after the ECU has its card back, so it costs no logging downtime.
     */
    private fun finalize(p: Pending, dest: DocumentFile, log: (String) -> Unit): Boolean = try {
        val bad = verifyDestination(p.tmp, p.written, p.crc)
        if (bad != null) {
            log("  VERIFY FAILED ${p.name}: $bad - not recording, will retry next sync")
            p.tmp.delete()
            false
        } else {
            dest.findFile(p.name)?.delete()
            if (!p.tmp.renameTo(p.name)) {
                log("  Could not rename ${p.name}.part - not recording")
                false
            } else {
                // Key on the CARD's size so the next sync still recognises this file.
                history.markCopied(p.name, p.cardSize)
                true
            }
        }
    } catch (e: Exception) {
        log("  VERIFY ERROR ${p.name}: ${e.message} - will retry next sync")
        false
    }

    /**
     * The LUN may take a moment to attach, and this ECU drops commands often
     * enough that a single probe is not good enough.
     */
    private fun openCardWithRetry(card: CardReader, log: (String) -> Unit): String? {
        var lastError: String? = null
        // Fewer, longer waits: each attempt re-claims the USB interface, and the
        // ECU's USB stack is known to stall when a medium-less LUN is probed.
        for (attempt in 1..3) {
            if (!active()) return "Cancelled before the card was opened"
            Thread.sleep(if (attempt == 1) 2000 else 2500)
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
        log: (String) -> Unit,
        index: Int,
        count: Int,
        bytesBefore: Long,
        bytesTotal: Long,
        progress: (Progress?) -> Unit,
        pending: MutableList<Pending>
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

            log("Copying $name (${fmtMb(size)})...")

            val sourceCrc = CRC32()
            val started = System.currentTimeMillis()
            var lastUi = 0L
            var soFar = 0L

            var records = -1
            val written = context.contentResolver.openOutputStream(tmp.uri)!!.use { os ->
                val (bytes, recs) = card.copyTrimmed(f, os, ::active) { chunk, len ->
                    sourceCrc.update(chunk, 0, len)
                    soFar += len
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 250) {          // ~4 updates/sec, not per 64 kB
                        lastUi = now
                        val secs = (now - started) / 1000.0
                        val rate = if (secs > 0.3) soFar / secs else 0.0
                        val pct = if (size > 0) (soFar * 100 / size) else 0
                        val overall = if (bytesTotal > 0)
                            ((bytesBefore + soFar) * 100 / bytesTotal) else 0
                        progress(
                            Progress(
                                fileIndex = index,
                                fileCount = count,
                                fileName = name,
                                overallPercent = overall.toInt(),
                                detail = "$pct% of this file  ${fmtMb(soFar)}/${fmtMb(size)}" +
                                    "   ${fmtRate(rate)}\n" +
                                    etaText(bytesTotal - bytesBefore - soFar, rate)
                            )
                        )
                    }
                }
                records = recs
                bytes
            }

            if (!active()) { tmp.delete(); return FileResult.CANCELLED }

            // A trimmed .mlg is legitimately shorter than the card copy: the ECU
            // pre-allocates 32 MB and never truncates. Only an UNtrimmed file must
            // match the card length exactly.
            if (records < 0 && written != size) {
                log("  SIZE MISMATCH $name: got $written of $size - not recording")
                tmp.delete()
                return FileResult.FAILED
            }
            if (records >= 0) {
                val saved = 100 - (written * 100 / maxOf(size, 1))
                log("  trimmed padding: ${fmtMb(size)} -> ${fmtMb(written)} " +
                    "($records records, $saved% less to transfer)")
            }

            // Verification is DEFERRED until after the ECU has its card back.
            // Closing a SAF stream does not mean the bytes are durable - cloud
            // providers buffer and can fail an upload later - so it still happens,
            // just off the critical path where it costs no logging downtime.
            pending += Pending(
                tmp = tmp,
                name = name,
                cardSize = size,          // key history on the CARD size, not the trimmed size
                written = written,
                crc = sourceCrc.value
            )
            FileResult.COPIED
        } catch (e: Exception) {
            log("  FAILED $name: ${e.message}")
            runCatching { dest.findFile("$name.part")?.delete() }
            FileResult.FAILED
        }
    }

    private fun fmtMb(bytes: Long): String =
        if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1048576.0)
        else "%d kB".format(bytes / 1024)

    private fun fmtRate(bytesPerSec: Double): String =
        if (bytesPerSec <= 0) "" else "%.0f kB/s".format(bytesPerSec / 1024.0)

    private fun etaText(bytesLeft: Long, rate: Double): String {
        if (rate <= 0 || bytesLeft <= 0) return ""
        val secs = (bytesLeft / rate).toInt()
        return if (secs >= 60) "about ${secs / 60}m ${secs % 60}s left"
        else "about ${secs}s left"
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
