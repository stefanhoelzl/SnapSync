package app.snapsync.ui.components

import kotlinx.datetime.LocalDateTime

/**
 * A capture-date **range** as one readable label — the participation section's single statement of what
 * will be shared.
 *
 * In the design system rather than beside the reduction because it reads no clock and no zone: it formats
 * two wall-clock values, exactly as [appDateTimeLabel] and [appDateLabel] do, and how a date READS is the
 * design system's business (capability `design-system`). The reduction decides what the dates ARE.
 *
 * The shape adapts to what the range actually is, so the common cases read as a person would say them: a
 * single day with a time span, whole days as a day span, and anything else spelled out at both ends.
 */
fun appRangeLabel(from: LocalDateTime, until: LocalDateTime): String {
    val sameDay = from.year == until.year && from.month == until.month && from.day == until.day
    val wholeDays = from.hour == 0 && from.minute == 0 && from.second == 0 &&
        until.hour == 0 && until.minute == 0 && until.second == 0
    val sameMonth = from.month == until.month && from.year == until.year
    return when {
        sameDay -> "${dayMon(from)}, ${hhmm(from)}–${hhmm(until)}"
        wholeDays && sameMonth -> "${from.day}–${until.day} ${mon(until)} ${until.year}"
        wholeDays -> "${dayMon(from)} – ${dayMon(until)} ${until.year}"
        else -> "${dayMon(from)} ${hhmm(from)} – ${dayMon(until)} ${hhmm(until)}"
    }
}

// The pieces the label is built from. Private to the design system, like every other rendering detail
// here — a month abbreviation is how a date READS, and nothing outside this module names one.
private fun rangeP2(n: Int) = n.toString().padStart(2, '0')
private fun hhmm(d: LocalDateTime) = "${rangeP2(d.hour)}:${rangeP2(d.minute)}"
private fun mon(d: LocalDateTime) = RANGE_MONTHS[d.month.ordinal]
private fun dayMon(d: LocalDateTime) = "${d.day} ${mon(d)}"

private val RANGE_MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
