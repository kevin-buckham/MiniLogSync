package io.github.kevinbuckham.minilogsync

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * "How far back should this sync go?"
 *
 * A new phone (or a cleared history) sees every log on the card as new - 140 logs
 * at 32 MB each is the better part of an hour in the car, when the owner usually
 * wants the last day or two. So when a sync finds more than a threshold of new logs
 * it offers a cutoff by age, and copies everything if nobody answers.
 *
 * Age comes from the filename (re_YYMMDD_HHMMSS.mlg, the ECU's RTC). A log named
 * from rusEFI's counter fallback has no date, so it is NEVER skipped: its age is
 * unknown, and silently dropping a log is the one outcome this app must not have.
 */
object SyncScope {

    private val datePattern = Regex("""^re_(\d{6}_\d{6})\.mlg$""", RegexOption.IGNORE_CASE)
    private val fmt = DateTimeFormatter.ofPattern("yyMMdd_HHmmss")

    /** When the log was started, per the ECU clock; null for counter-named logs. */
    fun logTime(name: String): LocalDateTime? {
        val m = datePattern.matchEntire(name) ?: return null
        return try {
            LocalDateTime.parse(m.groupValues[1], fmt)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Newest dated log first, then undated ones - the order to copy in. */
    fun <T> newestFirst(items: List<T>, name: (T) -> String): List<T> {
        val (dated, undated) = items.partition { logTime(name(it)) != null }
        return dated.sortedByDescending { logTime(name(it)) } + undated.sortedBy(name)
    }

    /**
     * One choice offered to the owner.
     * @param days logs older than this many days are skipped; null = copy everything.
     */
    data class Option(val label: String, val days: Int?, val count: Int, val bytes: Long)

    /** Day cutoffs offered, smallest first. 0 = "none, start from now". */
    private val CUTOFFS = listOf(1, 2, 7, 0)

    /**
     * The choices for [names] (with their sizes), measured against [now].
     * A cutoff that would copy everything anyway is left out - it is not a choice.
     */
    fun options(sizes: Map<String, Long>, now: LocalDateTime): List<Option> {
        val all = Option("Everything", null, sizes.size, sizes.values.sum())
        val out = mutableListOf<Option>()
        for (days in CUTOFFS) {
            val kept = sizes.filterKeys { !isTooOld(it, days, now) }
            if (kept.size == sizes.size) continue
            val label = when (days) {
                0 -> "None - only logs made from now on"
                1 -> "Last day"
                7 -> "Last week"
                else -> "Last $days days"
            }
            out += Option(label, days, kept.size, kept.values.sum())
        }
        return out + all
    }

    /** "Last 2 days  (6 logs, 190 MB)" */
    fun describe(o: Option): String {
        val size = when {
            o.bytes >= 1L shl 30 -> "%.1f GB".format(o.bytes / 1073741824.0)
            else -> "%d MB".format(o.bytes / 1048576)
        }
        return "${o.label}  (${o.count} log${if (o.count == 1) "" else "s"}, $size)"
    }

    /** Would a cutoff of [days] skip this log? Undated logs are never skipped. */
    fun isTooOld(name: String, days: Int?, now: LocalDateTime): Boolean {
        if (days == null) return false
        val t = logTime(name) ?: return false
        return t.isBefore(now.minusDays(days.toLong()))
    }
}
