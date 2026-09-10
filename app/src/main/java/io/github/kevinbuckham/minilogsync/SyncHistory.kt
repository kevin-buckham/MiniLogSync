package io.github.kevinbuckham.minilogsync

import android.content.Context
import org.json.JSONObject

/**
 * Which files we have already copied.
 *
 * Deliberately tracked HERE and not by looking at the destination: the owner may
 * delete files from the phone or the cloud folder, and that must not cause a re-copy
 * (spec section 5). History is PERMANENT, so a false positive means that log is never
 * copied again - silently.
 *
 * THE KEY MUST NOT BE THE FILENAME ALONE. The original design said "the filename is a
 * sufficient key", paired with the file size as a tie-break. Both premises were wrong:
 *  - Every log on the card is rusEFI's 32 MB pre-allocation, so size discriminates
 *    almost nothing.
 *  - rusEFI names logs from the RTC (re_YYMMDD_HHMMSS.mlg), but falls back to a
 *    COUNTER when the clock is not set - and the clock is not set after a battery
 *    disconnect. A counter-named log can therefore reuse a name already in history,
 *    at which point the new log is classified "already had" forever, with no error.
 *
 * So the key carries a content stamp: the MLG header's own timestamp. Two different
 * logs cannot share it.
 *
 * Legacy entries (recorded before the stamp existed) are honoured only for
 * date-patterned names, which the counter fallback cannot produce.
 */
class SyncHistory(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sync_history", Context.MODE_PRIVATE)

    /** filename -> "size:stamp", or a bare size for legacy entries. */
    private val copied: MutableMap<String, String> = load()

    private val datePattern = Regex("""^re_\d{6}_\d{6}\.mlg$""", RegexOption.IGNORE_CASE)

    private fun load(): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        val raw = prefs.getString("copied", null) ?: return out
        runCatching {
            val obj = JSONObject(raw)
            obj.keys().forEach { k -> out[k] = obj.get(k).toString() }
        }
        return out
    }

    private fun save() {
        val obj = JSONObject()
        copied.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("copied", obj.toString()).apply()
    }

    /**
     * @param stamp content stamp from CardReader.contentStamp, or 0 if unavailable.
     *
     * A stamp of 0 must NEVER fall back to size-only matching. That is precisely how
     * the previous attempt re-created the bug it was meant to fix: it stored
     * "size:0" for every file, took the has-a-stamp branch, and matched on size -
     * which takes only a handful of values - while ALSO bypassing the date-name guard
     * that the legacy path at least kept. With no usable stamp we require a filename
     * the ECU cannot reuse.
     */
    fun isCopied(name: String, size: Long, stamp: Long): Boolean = synchronized(copied) {
        val rec = copied[name] ?: return false
        val parts = rec.split(':')
        val recSize = parts[0].toLongOrNull() ?: return false
        if (recSize != size) return false

        val recStamp = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        if (stamp != 0L && recStamp != 0L) {
            // Both sides have a content stamp: it decides, and it is the only thing
            // that can tell two same-named logs apart.
            return recStamp == stamp
        }
        // No usable stamp on one side or the other. Name+size is safe only where the
        // ECU cannot produce that name twice: rusEFI names logs from the RTC
        // (re_YYMMDD_HHMMSS) but falls back to a COUNTER when the clock is unset,
        // e.g. after a battery disconnect, and a counter name CAN come round again.
        return datePattern.matches(name)
    }

    /**
     * Does deciding this file actually require reading a content stamp off the card?
     *
     * Only when the name is already recorded AND the ECU could produce that name
     * again. rusEFI names logs from the RTC (re_YYMMDD_HHMMSS) and only falls back to
     * a counter when the clock is unset, so a date-patterned name is already unique
     * and needs no stamp. Every one of the 271 logs this car has produced is
     * date-patterned - so in practice this returns false and the sync does ZERO extra
     * reads before copying. Stamping every file up front made the app appear to hang
     * before it started, which defeats the point of not fetching a laptop.
     */
    fun needsStamp(name: String): Boolean = synchronized(copied) {
        copied.containsKey(name) && !datePattern.matches(name)
    }

    /** Only called after a copy has been verified. */
    fun markCopied(name: String, size: Long, stamp: Long) = synchronized(copied) {
        copied[name] = "$size:$stamp"
        save()
    }

    fun forgetAll() = synchronized(copied) {
        copied.clear()
        save()
    }

    val count: Int get() = synchronized(copied) { copied.size }
}
