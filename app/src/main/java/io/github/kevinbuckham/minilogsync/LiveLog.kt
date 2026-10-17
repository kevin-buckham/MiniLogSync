package io.github.kevinbuckham.minilogsync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds the .ini that matches the ECU's firmware signature.
 *
 * rusEFI publishes the ini for every official build at a URL derived from the
 * signature (java_console SignatureHelper.getUrl), so the first live log with signal
 * downloads it and later ones - in a car park with no coverage - use the cache.
 * Custom-built firmware is not published; for that the owner loads the file by hand,
 * and it is still checked against the ECU's signature, because decoding with the
 * wrong ini silently produces plausible-looking garbage.
 */
object IniStore {

    private fun dir(ctx: Context) = File(ctx.filesDir, "ini").apply { mkdirs() }

    private fun cacheFile(ctx: Context, signature: String) =
        File(dir(ctx), signature.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".ini")

    /** "rusEFI master.2026.10.17.uaefi_pro.1333794255" -> its rusefi.com URL. */
    fun urlFor(signature: String): String? {
        if (!signature.startsWith("rusEFI ")) return null
        val p = signature.removePrefix("rusEFI ").split('.')
        if (p.size < 6) return null
        val n = p.size
        val branch = p.subList(0, n - 5).joinToString(".")
        return "https://rusefi.com/online/ini/rusefi/$branch/${p[n - 5]}/${p[n - 4]}/" +
            "${p[n - 3]}/${p[n - 2]}/${p[n - 1]}.ini"
    }

    /** Cached, else downloaded. Returns null (having logged why) when neither works. */
    fun load(ctx: Context, signature: String, log: (String) -> Unit): IniModel? {
        val cached = cacheFile(ctx, signature)
        if (cached.length() > 10_000) {
            log("Using cached ini for this firmware")
            return parseChecked(cached, signature, log)
        }
        val url = urlFor(signature)
        if (url == null) {
            log("Signature is not an official rusEFI build - load the .ini by hand (Advanced)")
            return null
        }
        log("Downloading ini: $url")
        val tmp = File(cached.path + ".tmp")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
        }
        return try {
            if (conn.responseCode >= 300) {
                log("ini download failed: HTTP ${conn.responseCode}")
                null
            } else {
                conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                tmp.renameTo(cached)
                log("ini cached (${cached.length() / 1024} kB)")
                parseChecked(cached, signature, log)
            }
        } catch (e: Exception) {
            log("ini download failed: ${e.message} - no signal? Load the .ini by hand (Advanced)")
            null
        } finally {
            tmp.delete()
            conn.disconnect()
        }
    }

    /** A hand-picked ini is accepted only if its signature is one we can check later. */
    fun importManual(ctx: Context, uri: Uri, log: (String) -> Unit): Boolean = try {
        val tmp = File(dir(ctx), "import.tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { inp ->
            tmp.outputStream().use { inp.copyTo(it) }
        }
        val model = tmp.bufferedReader().use { IniModel.parse(it) }
        if (model.signature.isBlank() || model.blockSize <= 0) {
            log("That file is not a TunerStudio ini (no signature / ochBlockSize)")
            tmp.delete()
            false
        } else {
            tmp.renameTo(cacheFile(ctx, model.signature))
            log("ini loaded for '${model.signature}'")
            true
        }
    } catch (e: Exception) {
        log("Could not read that ini: ${e.message}")
        false
    }

    private fun parseChecked(f: File, signature: String, log: (String) -> Unit): IniModel? {
        val m = f.bufferedReader().use { IniModel.parse(it) }
        if (m.signature != signature) {
            log("ini signature '${m.signature}' does not match the ECU - not using it")
            return null
        }
        if (m.blockSize <= 0) {
            log("ini has no ochBlockSize - not using it")
            return null
        }
        return m
    }
}

/**
 * Live datalogging over USB, the way TunerStudio does it: poll the output-channel
 * block with 'O' and write each sample as an .mlg record.
 *
 * This is a TEST build of the feature. Its job is to measure what the phone
 * achieves (rate, errors) and to produce a file to open in MegaLogViewer; the armed
 * "log the next start" mode builds on it once those numbers are known.
 *
 * Read-only throughout: it sends only 'S' and 'O', which change nothing on the ECU,
 * and it never touches the SD card, so the ECU's own logging carries on.
 */
class LiveLogJob(
    private val context: Context,
    private val link: EcuLink,
    private val destTree: Uri,
    private val targetHz: Int = 100
) {
    data class Stats(
        val seconds: Double, val samples: Long, val hz: Double,
        val errors: Long, val bytes: Long, val file: String
    )

    private val stopFlag = AtomicBoolean(false)
    fun stop() = stopFlag.set(true)

    /** Converts one output-channel block into one MLG record. */
    private class Slot(val field: MlgWriter.Field, val write: (ByteArray, ByteArray, Int, Float) -> Unit)

    fun run(log: (String) -> Unit, stats: (Stats) -> Unit) {
        val sigBytes = link.request(TsPacket.frame(TsPacket.CODE_HELLO, ByteArray(0)), 256, 1000)
        if (sigBytes == null) { log("ECU did not answer the signature request"); return }
        val signature = String(sigBytes, Charsets.US_ASCII).substringBefore('\u0000').trim()
        log("Firmware: $signature")

        val ini = IniStore.load(context, signature, log) ?: return
        val chunk = (if (ini.blockingFactor > 0) ini.blockingFactor else 256)
            .coerceAtMost(ini.blockSize)
        val slots = plan(ini, log)
        if (slots.isEmpty()) { log("No loggable channels in the ini"); return }

        val dest = DocumentFile.fromTreeUri(context, destTree)
        if (dest == null || !dest.canWrite()) { log("Destination folder is not writable"); return }
        val name = "live_" + SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date()) + ".mlg"
        val doc = dest.createFile("application/octet-stream", name)
            ?: run { log("Could not create $name"); return }
        val raw = context.contentResolver.openOutputStream(doc.uri)
            ?: run { log("Could not open $name"); return }

        log("Logging ${slots.size} channels to $name - block ${ini.blockSize} bytes " +
            "in ${(ini.blockSize + chunk - 1) / chunk} request(s), target $targetHz Hz")

        val out = java.io.BufferedOutputStream(raw, 256 * 1024)
        val counting = object : java.io.FilterOutputStream(out) {
            var n = 0L
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); n += len }
            override fun write(b: Int) { out.write(b); n++ }
        }
        val info = "\"MiniLogSync live log\"\n\"$signature\"\n\"Capture Date: ${Date()}\""
        val writer = MlgWriter(counting, slots.map { it.field }, info, System.currentTimeMillis() / 1000)
        val record = ByteArray(writer.recordLength)
        val block = ByteArray(ini.blockSize)

        val t0 = System.nanoTime()
        val periodNs = 1_000_000_000L / targetHz.coerceIn(1, 1000)
        var samples = 0L
        var errors = 0L
        var failStreakSince = 0L
        var lastStats = 0L
        var windowStart = t0
        var windowSamples = 0L
        var hz = 0.0

        try {
            while (!stopFlag.get()) {
                if (readBlock(ini.blockSize, chunk, block)) {
                    failStreakSince = 0L
                    val now = System.nanoTime()
                    val elapsedUs = (now - t0) / 1000
                    var pos = 0
                    val tSec = (elapsedUs / 1e6).toFloat()
                    for (s in slots) {
                        s.write(block, record, pos, tSec)
                        pos += MlgWriter.sizeOf(s.field.type)
                    }
                    writer.writeRecord(record, elapsedUs)
                    samples++
                    windowSamples++
                } else {
                    errors++
                    val now = System.nanoTime()
                    if (failStreakSince == 0L) failStreakSince = now
                    // A key-off or cable pull looks like a run of failures. Give a brief
                    // glitch time to clear, then stop rather than spin.
                    if (!link.isDevicePresent()) { log("ECU disconnected - stopping"); break }
                    if (now - failStreakSince > 5_000_000_000L) {
                        log("No good reply for 5 s - stopping"); break
                    }
                }

                val now = System.nanoTime()
                if (now - windowStart >= 1_000_000_000L) {
                    hz = windowSamples * 1e9 / (now - windowStart)
                    windowStart = now
                    windowSamples = 0
                }
                if (now - lastStats >= 500_000_000L) {
                    lastStats = now
                    stats(Stats((now - t0) / 1e9, samples, hz, errors, counting.n, name))
                }
                // Pace like TunerStudio's runtime rate; sleep only if ahead of schedule.
                val next = t0 + (samples + errors) * periodNs
                val ahead = next - System.nanoTime()
                if (ahead > 1_000_000L) Thread.sleep(ahead / 1_000_000L)
            }
        } finally {
            runCatching { counting.flush() }
            runCatching { raw.close() }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        val avg = if (secs > 0) samples / secs else 0.0
        stats(Stats(secs, samples, avg, errors, counting.n, name))
        log("Live log saved: $name - $samples samples in %.1f s (avg %.1f Hz), %d failed reads, %.1f MB"
            .format(secs, avg, errors, counting.n / 1048576.0))
    }

    /** Whole block via one or more 'O' requests of at most [chunk] bytes each. */
    private fun readBlock(size: Int, chunk: Int, into: ByteArray): Boolean {
        var off = 0
        while (off < size) {
            val n = minOf(chunk, size - off)
            val reply = link.request(TsPacket.outputRequest(off, n), n) ?: return false
            if (reply.size != n) return false
            reply.copyInto(into, off)
            off += n
        }
        return true
    }

    /** Datalog entries -> MLG fields with a decoder each; unresolvable ones are listed. */
    private fun plan(ini: IniModel, log: (String) -> Unit): List<Slot> {
        val slots = ArrayList<Slot>()
        val skipped = ArrayList<String>()
        for (e in ini.datalog) {
            if (e.channel == "time") {
                slots += Slot(MlgWriter.Field(e.label, "s", MlgWriter.F32, 1f, 0f, 3)) { _, rec, pos, t ->
                    putBe(rec, pos, java.lang.Float.floatToRawIntBits(t).toLong(), 4)
                }
                continue
            }
            when (val c = ini.channels[e.channel]) {
                is IniModel.Channel.Scalar -> {
                    val type = MlgWriter.typeOf(c.type)
                    val size = type?.let(MlgWriter::sizeOf)
                    if (type == null || size == null || c.offset + size > ini.blockSize) {
                        skipped += e.channel; continue
                    }
                    val off = c.offset
                    // Same bytes, LE in the block -> BE in the log; scale/translate
                    // carry over unchanged because MLG applies the same formula.
                    slots += Slot(MlgWriter.Field(e.label, c.units, type, c.scale.toFloat(),
                                                  c.translate.toFloat(), e.digits)) { blk, rec, pos, _ ->
                        for (i in 0 until size) rec[pos + i] = blk[off + size - 1 - i]
                    }
                }
                is IniModel.Channel.Bits -> {
                    val size = MlgWriter.typeOf(c.type)?.let(MlgWriter::sizeOf)
                    if (size == null || c.offset + size > ini.blockSize) { skipped += e.channel; continue }
                    val width = c.hi - c.lo + 1
                    val outType = if (width <= 8) MlgWriter.U08 else MlgWriter.U16
                    val outSize = MlgWriter.sizeOf(outType)
                    val mask = (1L shl width) - 1
                    val off = c.offset
                    val lo = c.lo
                    slots += Slot(MlgWriter.Field(e.label, "", outType, 1f, 0f, 0)) { blk, rec, pos, _ ->
                        var v = 0L
                        for (i in 0 until size) v = v or ((blk[off + i].toLong() and 0xFF) shl (8 * i))
                        putBe(rec, pos, (v ushr lo) and mask, outSize)
                    }
                }
                null -> skipped += e.channel
            }
        }
        if (skipped.isNotEmpty()) {
            log("Skipped ${skipped.size} datalog entr${if (skipped.size == 1) "y" else "ies"} " +
                "with no plain channel: ${skipped.take(12).joinToString()}" +
                if (skipped.size > 12) ", …" else "")
        }
        return slots
    }

    private fun putBe(rec: ByteArray, pos: Int, v: Long, size: Int) {
        for (i in 0 until size) rec[pos + i] = (v shr (8 * (size - 1 - i))).toByte()
    }
}
