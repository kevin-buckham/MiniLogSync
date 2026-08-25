package io.github.kevinbuckham.minilogsync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * The whole one-button sync: mount -> read card -> copy new files -> restore.
 *
 * Ordering matters. libaums skips LUNs with no medium, so the card must be
 * attached (`sdmode pc`) BEFORE the storage layer is initialised.
 *
 * The ECU is always handed back its card, including on failure (spec section 7).
 */
class SyncJob(
    private val context: Context,
    private val link: EcuLink,
    private val history: SyncHistory
) {

    data class Progress(val line: String)

    fun run(destTree: Uri, log: (String) -> Unit): Boolean {
        val dest = DocumentFile.fromTreeUri(context, destTree)
        if (dest == null || !dest.canWrite()) {
            log("Destination folder is not writable - pick it again")
            return false
        }

        // 1. hand the card to us
        val mount = link.sendCommand(EcuLink.CMD_MOUNT_PHONE)
        if (!mount.ok) {
            log("Mount failed: ${mount.message}")
            return false
        }
        log("Card mounted; ECU is not logging")

        val card = CardReader(context)
        var ok = false
        try {
            // The ECU needs a moment to attach the LUN before it will enumerate.
            Thread.sleep(1500)

            val err = card.open()
            if (err != null) {
                log(err)
                return false
            }

            val files = card.listFiles().sortedBy { it.name }
            log("Card has ${files.size} files")

            var copiedCount = 0
            var skipped = 0
            var failed = 0

            for (f in files) {
                val name = f.name
                val size = f.length
                if (history.isCopied(name, size)) { skipped++; continue }

                // temp name first, rename on success, so a partial copy is never
                // mistaken for a good file (spec section 7)
                val tmpName = "$name.part"
                dest.findFile(tmpName)?.delete()
                val tmp = dest.createFile("application/octet-stream", tmpName)
                if (tmp == null) { log("Could not create $tmpName"); failed++; continue }

                log("Copying $name (${size / 1024} kB)...")
                val written = try {
                    context.contentResolver.openOutputStream(tmp.uri)!!.use { os ->
                        card.copyTo(f, os) { }
                    }
                } catch (e: Exception) {
                    log("  FAILED $name: ${e.message}")
                    tmp.delete()
                    failed++
                    continue
                }

                if (written != size) {
                    log("  SIZE MISMATCH $name: got $written of $size - not recording")
                    tmp.delete()
                    failed++
                    continue
                }

                dest.findFile(name)?.delete()
                if (!tmp.renameTo(name)) {
                    log("  Could not rename $tmpName - not recording")
                    failed++
                    continue
                }

                history.markCopied(name, size)
                copiedCount++
            }

            log("Done: $copiedCount new, $skipped already had, $failed failed")
            ok = failed == 0
        } catch (e: Exception) {
            log("Sync error: ${e.message}")
        } finally {
            card.close()
            // ALWAYS give the card back, whatever happened above.
            val restore = link.sendCommand(EcuLink.CMD_RESTORE_AUTO)
            log(if (restore.ok) "Card returned to ECU - logging resumed"
                else "WARNING: could not restore ECU logging (${restore.message})")
        }
        return ok
    }
}
