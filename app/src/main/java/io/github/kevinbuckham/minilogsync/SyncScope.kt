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
 *
 * Cutoffs are measured back from the NEWEST dated log on the card, not from the
 * phone's clock. rusEFI accepts any RTC year from 2016 to 2030, so a drifted clock,
 * one set in UTC, or one stale after a battery disconnect produces names that look
 * hours or months old against the phone - and a phone-clock cutoff would then mark
 * the newest logs synced, permanently. Measured from the newest log, the newest log
 * is never skipped whatever the ECU clock says; only the spacing between logs
 * matters, and that comes from one clock. It also reads better: after a week parked,
 * "last 2 days" is the last 2 days of driving, not nothing.
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

    /**
     * Day cutoffs offered, smallest first. There is deliberately no "none / start from
     * now": it skipped everything dated, sat one row above the safe default in a dialog
     * answered in a car, and its only undo re-offers every log ever copied.
     */
    private val CUTOFFS = listOf(1, 2, 7)

    /** The newest dated log - what cutoffs are measured from; null if none is dated. */
    fun reference(names: Collection<String>): LocalDateTime? =
        names.mapNotNull(::logTime).maxOrNull()

    /**
     * The choices for [sizes] (name to size), measured back from [ref]. "Everything"
     * comes FIRST - it is the default and the safe answer. A cutoff that would copy
     * everything anyway is left out; it is not a choice.
     */
    fun options(sizes: Map<String, Long>, ref: LocalDateTime): List<Option> {
        val out = mutableListOf(Option("Everything", null, sizes.size, sizes.values.sum()))
        for (days in CUTOFFS) {
            val kept = sizes.filterKeys { !isTooOld(it, days, ref) }
            if (kept.size == sizes.size) continue
            val label = when (days) {
                1 -> "Last day of logs"
                7 -> "Last week of logs"
                else -> "Last $days days of logs"
            }
            val opt = Option(label, days, kept.size, kept.values.sum())
            // Two cutoffs that keep the same logs are one choice; show the wider window.
            if (out.size > 1 && out.last().count == opt.count) out[out.size - 1] = opt
            else out += opt
        }
        return out
    }

    /** "Last 2 days  (6 logs, 190 MB)" */
    fun describe(o: Option): String {
        val size = when {
            o.bytes >= 1L shl 30 -> "%.1f GB".format(o.bytes / 1073741824.0)
            else -> "%d MB".format(o.bytes / 1048576)
        }
        return "${o.label}  (${o.count} log${if (o.count == 1) "" else "s"}, $size)"
    }

    /**
     * Would a cutoff of [days] before [ref] skip this log? Undated logs are never
     * skipped, and with [ref] the newest dated log, neither is that one.
     */
    fun isTooOld(name: String, days: Int?, ref: LocalDateTime): Boolean {
        if (days == null) return false
        val t = logTime(name) ?: return false
        return t.isBefore(ref.minusDays(days.toLong()))
    }
}
