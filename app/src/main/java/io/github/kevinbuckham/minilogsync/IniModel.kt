package io.github.kevinbuckham.minilogsync

import java.io.BufferedReader

/**
 * The slice of a rusEFI TunerStudio .ini that live logging needs - nothing more.
 *
 *  [TunerStudio]     signature           (must equal what the ECU reports for 'S')
 *  [Constants]       blockingFactor      (max bytes per 'O' request)
 *  [OutputChannels]  ochBlockSize, and   name = scalar, TYPE, offset, "units", scale, translate
 *                                        name = bits,   TYPE, offset, [lo:hi]
 *  [Datalog]         entry = channel, "Label", int|float, "%fmt"
 *
 * Expression channels (name = { ... }) are deliberately not evaluated: in the
 * generated rusEFI ini no Datalog entry uses one except `time`, which the logger
 * produces itself. Entries that cannot be resolved are reported, not guessed.
 *
 * The generated ini has no active #if blocks, so preprocessor lines are skipped.
 */
data class IniModel(
    val signature: String,
    val blockSize: Int,
    val blockingFactor: Int,
    val channels: Map<String, Channel>,
    val datalog: List<LogEntry>,
    /** [TunerStudio] defaultRuntimeRecordPerSec - TunerStudio's own logging rate; 0 if absent. */
    val defaultHz: Int = 0,
    /** Channels that were present but not understood, so they are not silently mis-decoded. */
    val warnings: List<String> = emptyList()
) {
    sealed class Channel {
        /** TS semantics: value = (raw + translate) * scale - the same as MLG's transform. */
        data class Scalar(
            val type: String, val offset: Int, val units: String,
            val scale: Double, val translate: Double
        ) : Channel()

        data class Bits(val type: String, val offset: Int, val lo: Int, val hi: Int) : Channel()
    }

    data class LogEntry(val channel: String, val label: String, val digits: Int)

    companion object {
        fun parse(reader: BufferedReader): IniModel {
            var section = ""
            var signature = ""
            var blockSize = 0
            var blockingFactor = 0
            val channels = HashMap<String, Channel>()
            val datalog = ArrayList<LogEntry>()
            var defaultHz = 0
            val warnings = ArrayList<String>()

            reader.forEachLine { raw ->
                val line = stripComment(raw).trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                if (line.startsWith("[")) {
                    section = line.substringAfter('[').substringBefore(']')
                    return@forEachLine
                }
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEachLine
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()

                when (section) {
                    "TunerStudio" -> when (key) {
                        "signature" -> signature = unquote(value)
                        "defaultRuntimeRecordPerSec" -> defaultHz = value.toIntOrNull() ?: 0
                    }
                    "Constants" -> if (key == "blockingFactor") {
                        blockingFactor = value.toIntOrNull() ?: 0
                    }
                    "OutputChannels" -> when (key) {
                        "ochBlockSize" -> blockSize = value.toIntOrNull() ?: 0
                        else -> parseChannel(key, value, warnings)?.let { channels[key] = it }
                    }
                    "Datalog" -> if (key == "entry") parseEntry(value)?.let { datalog += it }
                }
            }
            return IniModel(signature, blockSize, blockingFactor, channels, datalog, defaultHz, warnings)
        }

        private fun parseChannel(name: String, value: String, warnings: MutableList<String>): Channel? {
            val f = splitFields(value)
            if (f.size < 3) return null
            val type = f[1]
            val offset = f[2].toIntOrNull() ?: return null
            return when (f[0]) {
                "scalar" -> {
                    // Some scalars stop after the offset; TS then uses scale 1, translate 0.
                    // A scale that is present but not a number (an expression) is NOT
                    // defaulted to 1 - that would log a plausible, wrongly scaled value.
                    val scale = number(f.getOrNull(4), 1.0)
                    val translate = number(f.getOrNull(5), 0.0)
                    if (scale == null || translate == null) {
                        warnings += "$name (non-numeric scale/translate)"
                        return null
                    }
                    Channel.Scalar(
                        type, offset,
                        units = f.getOrNull(3)?.let(::unquote)?.takeUnless { it.startsWith("{") } ?: "",
                        scale = scale, translate = translate
                    )
                }
                "bits" -> {
                    val range = f.getOrNull(3)?.trim('[', ']')?.split(':') ?: return null
                    val lo = range.getOrNull(0)?.toIntOrNull() ?: return null
                    val hi = range.getOrNull(1)?.toIntOrNull() ?: lo
                    Channel.Bits(type, offset, lo, hi)
                }
                else -> null
            }
        }

        /** Absent -> [default]; present but not a number -> null. */
        private fun number(s: String?, default: Double): Double? =
            if (s.isNullOrBlank()) default else s.toDoubleOrNull()

        private fun parseEntry(value: String): LogEntry? {
            val f = splitFields(value)
            if (f.size < 2) return null
            val fmt = f.getOrNull(3)?.let(::unquote) ?: ""
            val digits = Regex("""%\.(\d+)f""").find(fmt)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (fmt.contains('d')) 0 else 2
            return LogEntry(f[0], unquote(f[1]), digits)
        }

        /** Split on commas that are not inside quotes, braces or brackets. */
        internal fun splitFields(s: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var quoted = false
            var depth = 0
            for (c in s) {
                when {
                    c == '"' -> { quoted = !quoted; cur.append(c) }
                    quoted -> cur.append(c)
                    c == '{' || c == '[' -> { depth++; cur.append(c) }
                    c == '}' || c == ']' -> { depth--; cur.append(c) }
                    c == ',' && depth == 0 -> { out += cur.toString().trim(); cur.clear() }
                    else -> cur.append(c)
                }
            }
            out += cur.toString().trim()
            return out
        }

        /** Drop a ';' comment, ignoring semicolons inside quotes. */
        private fun stripComment(s: String): String {
            var quoted = false
            for (i in s.indices) {
                val c = s[i]
                if (c == '"') quoted = !quoted
                else if (c == ';' && !quoted) return s.substring(0, i)
            }
            return s
        }

        private fun unquote(s: String): String = s.trim().removeSurrounding("\"")
    }
}
