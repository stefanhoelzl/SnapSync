package app.snapsync.ui.components

import androidx.compose.runtime.Composable
import app.snapsync.model.DateFormats
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.date_range_one_day
import app.snapsync.ui.components.resources.date_range_open
import app.snapsync.ui.components.resources.date_range_today
import app.snapsync.ui.components.resources.date_span
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import org.jetbrains.compose.resources.stringResource

/**
 * The design system's human rendering of a DAY, with no time of day — `14 Jul 2026`, in the user's locale.
 *
 * Public because a screen sometimes needs to state a day in prose, and a screen must never re-derive the app's
 * date format. A day rather than an instant because some statements are about one: the join gate's retention
 * line ("Shared photos are deleted on …", capability `event-lifetime`) would read as false precision with a
 * minute attached.
 */
@Composable
fun appDateLabel(value: LocalDateTime): String = LocalDateFormats.current.format(value, "yMMMd")

/** `14 Jul 2026, 18:00` — a human rendering of the start, in the device's local wall-clock terms. */
@Composable
internal fun formatStart(value: LocalDateTime): String = LocalDateFormats.current.format(value, "yMMMdjm")

/**
 * The design system's rendering of an event's RANGE (capability `sync-status`), in local wall-clock terms:
 * `Sun 12 Jul – Tue 14 Jul` across days and — for an event that starts and ends on one day — that day with its
 * times, `Today 18:00 – 23:00` or `Sat 18 Jul 18:00 – 23:00` (each in the user's locale). The year shows only
 * when the range crosses one. A [end] of `null` (a legacy membership with no stored end) renders as
 * `From Sun 12 Jul`.
 *
 * Every day names its month: CLDR has no portable month-less day (`Ed` reads `12 Sun` in en-US).
 *
 * An end at exactly midnight closes the day BEFORE it, so it is shown as that day: an event ending "Tue
 * 00:00" lasts through Monday, and printing Tuesday would add a day nobody can take a photo in.
 */
@Composable
fun appDateRangeLabel(start: LocalDateTime, end: LocalDateTime?, today: LocalDate): String {
    val dates = LocalDateFormats.current
    if (end == null) return stringResource(Res.string.date_range_open, dates.day(start.date, withYear = false))
    val lastDay = lastDayOf(start, end)
    return when {
        lastDay == start.date -> {
            val from = dates.format(start, "jm")
            val until = dates.format(end, "jm")
            if (start.date == today) {
                stringResource(Res.string.date_range_today, from, until)
            } else {
                stringResource(Res.string.date_range_one_day, dates.day(start.date, withYear = false), from, until)
            }
        }
        else -> {
            val crossesYear = start.year != lastDay.year
            stringResource(Res.string.date_span, dates.day(start.date, crossesYear), dates.day(lastDay, crossesYear))
        }
    }
}

/** The last day the event covers: an end at exactly midnight closes the day before it. */
private fun lastDayOf(start: LocalDateTime, end: LocalDateTime): LocalDate {
    val atMidnight = end.time == LocalTime(0, 0)
    return if (atMidnight && end.date > start.date) end.date.minus(1, DateTimeUnit.DAY) else end.date
}

private fun DateFormats.day(day: LocalDate, withYear: Boolean): String =
    format(LocalDateTime(day, LocalTime(0, 0)), if (withYear) "yMMMEd" else "MMMEd")
