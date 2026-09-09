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
     * @param stamp content stamp (MLG header timestamp), or 0 when unavailable.
     */
    fun isCopied(name: String, size: Long, stamp: Long): Boolean = synchronized(copied) {
        val rec = copied[name] ?: return false
        if (rec.contains(':')) {
            val parts = rec.split(':')
            val recSize = parts[0].toLongOrNull() ?: return false
            val recStamp = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            // A stamp of 0 means we could not read one (not an MLG, or a short read).
            // Fall back to size, which is all we ever had for those files anyway.
            return recSize == size && (stamp == 0L || recStamp == stamp)
        }
        // Legacy: size only. Trust it only where the name itself cannot be reused.
        val recSize = rec.toLongOrNull() ?: return false
        return recSize == size && datePattern.matches(name)
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
