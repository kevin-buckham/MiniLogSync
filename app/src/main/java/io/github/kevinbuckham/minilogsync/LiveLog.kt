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
 *
 * Nothing reaches the cache unless it parsed AND its signature matched. A captive
 * portal answering HTTP 200 with a login page, or a truncated download, used to be
 * cached first and checked second - and the size check then served that bad file
 * forever, with no way to recover short of a manual import.
 */
object IniStore {

    /** The real uaefi_pro ini is about 860 kB; this bounds a runaway or hostile reply. */
    private const val MAX_INI_BYTES = 8L * 1024 * 1024

    private fun dir(ctx: Context) = File(ctx.filesDir, "ini").apply { mkdirs() }

    private fun cacheFile(ctx: Context, signature: String) =
        File(dir(ctx), signature.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".ini")

    /** "rusEFI master.2026.10.17.uaefi_pro.1333794255" -> its rusefi.com URL. */
    fun urlFor(signature: String): String? {
        if (!signature.startsWith("rusEFI ")) return null
        val p = signature.removePrefix("rusEFI ").split('.')
        if (p.size < 6) return null
        // The signature comes from the ECU; never let it put '/', '..' or '?' in a URL.
        if (p.any { !it.matches(Regex("[A-Za-z0-9_-]+")) }) return null
        val n = p.size
        val branch = p.subList(0, n - 5).joinToString(".")
        return "https://rusefi.com/online/ini/rusefi/$branch/${p[n - 5]}/${p[n - 4]}/" +
            "${p[n - 3]}/${p[n - 2]}/${p[n - 1]}.ini"
    }

    /** The cached ini for this exact firmware, or null. An unusable cache file is deleted. */
    fun cached(ctx: Context, signature: String, log: (String) -> Unit): IniModel? {
        val cached = cacheFile(ctx, signature)
        if (!cached.exists()) return null
        val m = parseChecked(cached, signature, log)
        if (m != null) {
            log("Using cached ini for this firmware")
            return m
        }
        log("Cached ini is unusable - discarding it")
        cached.delete()
        return null
    }

    /**
     * Read the ini off the ECU's own built-in drive - the only source that always
     * matches the running build, custom firmware included, and needs no signal.
     *
     * The serial link is released for the read and reopened afterwards. No `sdmode`
     * command is sent: the INI drive is always attached, so the ECU keeps logging.
     */
    fun fromEcu(ctx: Context, signature: String, link: EcuLink, log: (String) -> Unit): IniModel? {
        log("Reading the ini from the ECU's built-in drive (the ECU keeps logging)...")
        link.releaseForStorage()
        try {
            var img: ByteArray? = null
            for (attempt in 1..3) {
                img = CardReader(ctx).readIniDrive(log)
                if (img != null) break
                Thread.sleep(500L * attempt)
            }
            if (img == null) { log("Could not read the ECU's ini drive"); return null }
            val vol = FatImage.open(img)
            if (vol == null) { log("The ECU's ini drive is not a FAT12/16 volume"); return null }
            // Every .ini is tried and only a signature match is accepted, which also
            // rejects junk such as macOS "._name.ini" AppleDouble files.
            for (e in vol.entries.filter { it.name.endsWith(".ini", ignoreCase = true) }) {
                val bytes = vol.read(e) ?: continue
                val m = runCatching { IniModel.parse(bytes.inputStream().bufferedReader()) }.getOrNull()
                    ?: continue
                if (m.signature == signature && m.blockSize > 0) {
                    cacheFile(ctx, signature).writeBytes(bytes)
                    log("ini read from the ECU (${e.name}, ${bytes.size / 1024} kB) and cached")
                    return m
                }
            }
            if (vol.entries.any { it.name.endsWith(".7z", ignoreCase = true) }) {
                log("The ECU's drive holds rusefi.ini.7z, which this app cannot unpack yet")
            } else {
                log("No ini matching this firmware on the ECU's drive")
            }
            return null
        } catch (e: Exception) {
            log("Reading the ECU's ini drive failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            val r = link.reopen()
            if (!link.isOpen) log("WARNING: could not reconnect to the ECU: $r")
        }
    }

    /** Download by signature from rusefi.com (official builds only). */
    fun download(ctx: Context, signature: String, log: (String) -> Unit): IniModel? {
        val cached = cacheFile(ctx, signature)
        val url = urlFor(signature)
        if (url == null) {
            log("This signature is not an official rusEFI build, so rusefi.com has no ini for it")
            return null
        }
        log("Downloading ini: $url")
        val tmp = File(cached.path + ".tmp")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = false   // a redirect off rusefi.com is not the ini
            }
            if (conn.responseCode != 200) {
                log(if (conn.responseCode == 404)
                        "rusefi.com has no ini for this firmware - is it a custom build?"
                    else "ini download failed: HTTP ${conn.responseCode}")
                return null
            }
            conn.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_INI_BYTES) throw java.io.IOException("larger than 8 MB")
                        out.write(buf, 0, n)
                    }
                }
            }
            val m = parseChecked(tmp, signature, log) ?: return null
            if (!tmp.renameTo(cached)) tmp.copyTo(cached, overwrite = true)
            log("ini cached (${cached.length() / 1024} kB)")
            m
        } catch (e: Exception) {
            log("ini download failed: ${e.message} - no signal?")
            null
        } finally {
            tmp.delete()
            conn?.disconnect()
        }
    }

    /** A hand-picked ini is accepted only if its signature is one we can check later. */
    fun importManual(ctx: Context, uri: Uri, log: (String) -> Unit): Boolean {
        val tmp = File(dir(ctx), "import.tmp")
        return try {
            ctx.contentResolver.openInputStream(uri)!!.use { inp ->
                tmp.outputStream().use { inp.copyTo(it) }
            }
            val model = tmp.bufferedReader().use { IniModel.parse(it) }
            if (model.signature.isBlank() || model.blockSize <= 0) {
                log("That file is not a TunerStudio ini (no signature / ochBlockSize)")
                false
            } else {
                val dest = cacheFile(ctx, model.signature)
                if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true)
                log("ini loaded for '${model.signature}'")
                true
            }
        } catch (e: Exception) {
            log("Could not read that ini: ${e.message}")
            false
        } finally {
            tmp.delete()
        }
    }

    private fun parseChecked(f: File, signature: String, log: (String) -> Unit): IniModel? {
        val m = try {
            f.bufferedReader().use { IniModel.parse(it) }
        } catch (e: Exception) {
            log("ini could not be read: ${e.message}")
            return null
        }
        if (m.signature != signature) {
            log("ini signature '${m.signature.take(80)}' does not match the ECU - not using it")
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
 * Datalog entries -> MLG fields, each with a decoder from the output-channel block.
 * Separate from the job so it can be tested without a phone or an ECU.
 */
object LivePlan {

    /** Writes one field of one record from one output-channel block. */
    class Slot(
        val field: MlgWriter.Field,
        val write: (block: ByteArray, record: ByteArray, pos: Int, seconds: Float) -> Unit
    )

    class Plan(val slots: List<Slot>, val skipped: List<String>) {
        val recordLength: Int get() = slots.sumOf { MlgWriter.sizeOf(it.field.type) }

        /** Decode [block] into [record] (big-endian, field order). */
        fun fill(block: ByteArray, record: ByteArray, seconds: Float) {
            var pos = 0
            for (s in slots) {
                s.write(block, record, pos, seconds)
                pos += MlgWriter.sizeOf(s.field.type)
            }
        }
    }

    fun build(ini: IniModel): Plan {
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
                    // Same bytes, LE in the block -> BE in the log. A byte reversal is
                    // exact for signed and float types too. Scale and translate carry over
                    // unchanged: TS shows (raw + translate) * scale, MLG (raw + transform) * scale.
                    slots += Slot(MlgWriter.Field(e.label, c.units, type, c.scale.toFloat(),
                                                  c.translate.toFloat(), e.digits)) { blk, rec, pos, _ ->
                        for (i in 0 until size) rec[pos + i] = blk[off + size - 1 - i]
                    }
                }
                is IniModel.Channel.Bits -> {
                    val size = MlgWriter.typeOf(c.type)?.let(MlgWriter::sizeOf)
                    val width = c.hi - c.lo + 1
                    // Bits must lie inside their storage type, or the value is silently cut.
                    if (size == null || c.offset + size > ini.blockSize || width !in 1..32 ||
                        c.lo < 0 || c.hi >= size * 8) {
                        skipped += e.channel; continue
                    }
                    val outType = when {
                        width <= 8 -> MlgWriter.U08
                        width <= 16 -> MlgWriter.U16
                        else -> MlgWriter.U32
                    }
                    val outSize = MlgWriter.sizeOf(outType)
                    val mask = (1L shl width) - 1
                    val off = c.offset
                    val lo = c.lo
                    slots += Slot(MlgWriter.Field(e.label, "", outType, 1f, 0f, 0)) { blk, rec, pos, _ ->
                        // Raw unsigned read: a bit field in an S32 is still just bits.
                        var v = 0L
                        for (i in 0 until size) v = v or ((blk[off + i].toLong() and 0xFF) shl (8 * i))
                        putBe(rec, pos, (v ushr lo) and mask, outSize)
                    }
                }
                null -> skipped += e.channel
            }
        }
        return Plan(slots, skipped)
    }

    private fun putBe(rec: ByteArray, pos: Int, v: Long, size: Int) {
        for (i in 0 until size) rec[pos + i] = (v shr (8 * (size - 1 - i))).toByte()
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
 * It sends only 'S' and 'O'. Neither changes configuration or touches the SD card,
 * so the ECU's own logging carries on; the firmware merely counts the requests (its
 * TS statistics), exactly as it does for TunerStudio.
 */
class LiveLogJob(
    private val context: Context,
    private val link: EcuLink,
    private val destTree: Uri,
    /** Samples per second; null = the ini's defaultRuntimeRecordPerSec, as TunerStudio uses. */
    private val targetHz: Int? = null
) {
    data class Stats(
        val seconds: Double, val samples: Long, val hz: Double,
        val errors: Long, val bytes: Long, val file: String
    )

    private val stopFlag = AtomicBoolean(false)
    fun stop() = stopFlag.set(true)

    /** [ecu] gets a short "which firmware, which ini" description once both are known. */
    fun run(log: (String) -> Unit, stats: (Stats) -> Unit, ecu: (String) -> Unit = {}) {
        // A few tries: stale bytes on the line make the first request fail (and drain).
        var sigBytes: ByteArray? = null
        for (attempt in 1..3) {
            if (stopFlag.get()) break
            sigBytes = link.request(TsPacket.frame(TsPacket.CODE_HELLO, ByteArray(0)), 256, 1000)
            if (sigBytes != null) break
        }
        if (sigBytes == null) { log("ECU did not answer the signature request"); return }
        val signature = String(sigBytes, Charsets.US_ASCII).substringBefore('\u0000').trim()
        log("Firmware: $signature")
        if (stopFlag.get()) { log("Stopped before logging began"); return }

        // Cache first; then the ECU's own drive (exact build, no signal needed); then
        // rusefi.com (official builds only).
        val ini = IniStore.cached(context, signature, log)
            ?: IniStore.fromEcu(context, signature, link, log)
            ?: IniStore.download(context, signature, log)
        if (ini == null) {
            log("*** No ini for this firmware. Load the matching .ini with " +
                "'Load .ini file' on the Live Log tab. ***")
            return
        }
        if (!link.isOpen) { log("Not connected to the ECU - stopping"); return }
        if (stopFlag.get()) { log("Stopped before logging began"); return }
        if (ini.warnings.isNotEmpty()) {
            log("ini: ${ini.warnings.size} channel(s) not understood and left out: " +
                ini.warnings.take(8).joinToString() + if (ini.warnings.size > 8) ", …" else "")
        }
        val chunk = (if (ini.blockingFactor > 0) ini.blockingFactor else 256)
            .coerceAtMost(ini.blockSize)
        val plan = LivePlan.build(ini)
        if (plan.skipped.isNotEmpty()) {
            log("Skipped ${plan.skipped.size} datalog entr${if (plan.skipped.size == 1) "y" else "ies"} " +
                "with no plain channel: ${plan.skipped.take(12).joinToString()}" +
                if (plan.skipped.size > 12) ", …" else "")
        }
        if (plan.slots.isEmpty()) { log("No loggable channels in the ini"); return }
        val hzTarget = (targetHz ?: ini.defaultHz.takeIf { it > 0 } ?: 100).coerceIn(1, 200)
        ecu("ECU: ${signature.removePrefix("rusEFI ")}\nini: matched ✓  ${plan.slots.size} channels, " +
            "${ini.blockSize}-byte block")

        val dest = DocumentFile.fromTreeUri(context, destTree)
        if (dest == null || !dest.canWrite()) { log("Destination folder is not writable"); return }
        val name = "live_" + SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date()) + ".mlg"
        val doc = dest.createFile("application/octet-stream", name)
            ?: run { log("Could not create $name"); return }
        val raw = context.contentResolver.openOutputStream(doc.uri)
            ?: run { log("Could not open $name"); doc.delete(); return }

        val out = java.io.BufferedOutputStream(raw, 256 * 1024)
        val counting = object : java.io.FilterOutputStream(out) {
            var n = 0L
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); n += len }
            override fun write(b: Int) { out.write(b); n++ }
        }
        val info = "\"MiniLogSync live log\"\n\"$signature\"\n\"Capture Date: ${Date()}\""
        val writer = try {
            MlgWriter(counting, plan.slots.map { it.field }, info, System.currentTimeMillis() / 1000)
        } catch (e: Exception) {
            log("Could not start $name: ${e.message}")
            runCatching { raw.close() }
            doc.delete()
            return
        }
        log("Logging ${plan.slots.size} channels to $name - block ${ini.blockSize} bytes " +
            "in ${(ini.blockSize + chunk - 1) / chunk} request(s), target $hzTarget Hz")

        val record = ByteArray(writer.recordLength)
        val block = ByteArray(ini.blockSize)
        val t0 = System.nanoTime()
        val periodNs = 1_000_000_000L / hzTarget
        var samples = 0L
        var errors = 0L
        var failStreakSince = 0L
        var lastStats = 0L
        var windowStart = t0
        var windowSamples = 0L
        var hz = 0.0
        var writeError: String? = null

        try {
            while (!stopFlag.get()) {
                if (readBlock(ini.blockSize, chunk, block)) {
                    failStreakSince = 0L
                    val elapsedUs = (System.nanoTime() - t0) / 1000
                    plan.fill(block, record, (elapsedUs / 1e6).toFloat())
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
        } catch (e: java.io.IOException) {
            writeError = e.message ?: e.javaClass.simpleName
            log("Writing $name failed: $writeError - stopping")
        } finally {
            // Flush and close are where a provider (Drive) reports a failed write. They
            // used to be swallowed, and the log then said "saved" regardless.
            try { counting.flush() } catch (e: Exception) { writeError = writeError ?: e.message }
            try { raw.close() } catch (e: Exception) { writeError = writeError ?: e.message }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        val avg = if (secs > 0) samples / secs else 0.0
        stats(Stats(secs, samples, avg, errors, counting.n, name))
        val summary = "$samples samples in %.1f s (avg %.1f Hz), %d failed reads, %.1f MB"
            .format(secs, avg, errors, counting.n / 1048576.0)
        if (writeError == null) {
            log("Live log saved: $name - $summary")
        } else {
            log("*** Live log $name MAY BE INCOMPLETE: $writeError *** ($summary)")
        }
    }

    /**
     * Whole block via one or more 'O' requests of at most [chunk] bytes each. Any failed
     * chunk abandons the sample; EcuLink has already waited for the line to go quiet, so
     * a late reply cannot be taken for the next chunk's.
     */
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
}
