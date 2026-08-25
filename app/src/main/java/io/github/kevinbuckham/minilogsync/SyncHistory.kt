package io.github.kevinbuckham.minilogsync

import android.content.Context
import org.json.JSONObject

/**
 * Which files we have already copied.
 *
 * Deliberately tracked HERE and not by looking at the destination: the user may
 * delete files from the phone or the cloud folder, and that must not cause a
 * re-copy (spec section 5). Log files are immutable once closed - the ECU cannot
 * write to the card while it is mounted to us - so the filename is a sufficient key.
 */
class SyncHistory(context: Context) {

    private val prefs = context.getSharedPreferences("sync_history", Context.MODE_PRIVATE)

    /** filename -> size copied */
    private val copied: MutableMap<String, Long> = load()

    private fun load(): MutableMap<String, Long> {
        val out = mutableMapOf<String, Long>()
        val raw = prefs.getString("copied", null) ?: return out
        runCatching {
            val obj = JSONObject(raw)
            obj.keys().forEach { k -> out[k] = obj.getLong(k) }
        }
        return out
    }

    private fun save() {
        val obj = JSONObject()
        copied.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("copied", obj.toString()).apply()
    }

    fun isCopied(name: String, size: Long): Boolean =
        copied[name]?.let { it == size } == true

    /** Only called after a copy has been verified. */
    fun markCopied(name: String, size: Long) {
        copied[name] = size
        save()
    }

    fun forgetAll() {
        copied.clear()
        save()
    }

    val count: Int get() = copied.size
}
