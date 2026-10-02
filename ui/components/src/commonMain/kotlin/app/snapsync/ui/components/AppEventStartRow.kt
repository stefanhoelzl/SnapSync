package app.snapsync.ui.components

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus


/**
 * The design system's human rendering of a DAY, with no time of day — `14 Jul 2026`.
 *
 * Public because a screen sometimes needs to state a day in prose, and a screen must never re-derive the app's
 * date format. A day rather than an instant because some statements are about one: the join gate's retention
 * line ("Shared photos are deleted on …", capability `event-lifetime`) would read as false precision with a
 * minute attached.
 */
fun appDateLabel(value: LocalDateTime): String =
    "${value.day} ${monthAbbrev(value.month.ordinal)} ${value.year}"

/** `14 Jul 2026, 18:00` — a human rendering of the start, in the device's local wall-clock terms. */
internal fun formatStart(value: LocalDateTime): String {
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${value.day} ${monthAbbrev(value.month.ordinal)} ${value.year}, " +
        "${p(value.hour)}:${p(value.minute)}"
}

/**
 * The design system's rendering of an event's RANGE (capability `sync-status`), in local wall-clock terms:
 * `Sun 12 – Tue 14 Jul` across days, `Tue 30 Jun – Thu 2 Jul` across months, and — for an event that starts
 * and ends on one day — that day with its times, `Today 18:00 – 23:00` or `Sat 18 Jul 18:00 – 23:00`.
 * The year shows only when the range crosses one. A [end] of `null` (a legacy membership with no stored end)
 * renders as `From Sun 12 Jul`.
 *
 * An end at exactly midnight closes the day BEFORE it, so it is shown as that day: an event ending "Tue
 * 00:00" lasts through Monday, and printing Tuesday would add a day nobody can take a photo in.
 */
fun appDateRangeLabel(start: LocalDateTime, end: LocalDateTime?, today: LocalDate): String {
    val lastDay = end?.let { lastDayOf(start, it) }
    return when {
        end == null || lastDay == null -> "From ${dayLabel(start.date, withMonth = true, withYear = false)}"
        lastDay == start.date -> {
            val day = if (start.date == today) "Today" else dayLabel(start.date, withMonth = true, withYear = false)
            "$day ${hhmm(start)} – ${hhmm(end)}"
        }
        else -> {
            val crossesYear = start.year != lastDay.year
            val crossesMonth = crossesYear || start.month != lastDay.month
            "${dayLabel(start.date, withMonth = crossesMonth, withYear = crossesYear)} – " +
                dayLabel(lastDay, withMonth = true, withYear = crossesYear)
        }
    }
}

/** The last day the event covers: an end at exactly midnight closes the day before it. */
private fun lastDayOf(start: LocalDateTime, end: LocalDateTime): LocalDate {
    val atMidnight = end.time == LocalTime(0, 0)
    return if (atMidnight && end.date > start.date) end.date.minus(1, DateTimeUnit.DAY) else end.date
}

private fun dayLabel(day: LocalDate, withMonth: Boolean, withYear: Boolean): String = buildString {
    append(WEEKDAYS[day.dayOfWeek.ordinal]).append(' ').append(day.day)
    if (withMonth) append(' ').append(monthAbbrev(day.month.ordinal))
    if (withYear) append(' ').append(day.year)
}

private fun hhmm(value: LocalDateTime): String {
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${p(value.hour)}:${p(value.minute)}"
}

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private fun monthAbbrev(monthOrdinal: Int): String = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)[monthOrdinal]
