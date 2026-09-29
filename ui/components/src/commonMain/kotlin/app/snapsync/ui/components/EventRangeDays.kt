package app.snapsync.ui.components

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

// What a tap or a drag on the calendar does to the range (capability `create-event`). Every result keeps the
// window valid — the last day never before the start's day nor past the window's length — so a bad range is
// unreachable rather than refused. The start's clock time is kept whenever its day moves.

/**
 * A tap on [day]. While the last day is pending, a day on or after the start places it, and a day before the
 * start moves the start there. Once the last day has been placed, the tap starts a new range at [day]: both
 * ends on that day, the end time blank, the last day pending again.
 */
internal fun EventRange.pickDay(day: LocalDate, latest: LatestUntil): EventRange = when {
    !endPending -> EventRange(from = LocalDateTime(day, from.time))
    day < from.date -> copy(from = LocalDateTime(day, from.time)).withinWindow(latest)
    else -> placeEnd(day, latest)
}

/** The last day dragged to [day]: it is placed there, held between the start's day and the window's end. */
internal fun EventRange.dragEndTo(day: LocalDate, latest: LatestUntil): EventRange = placeEnd(day, latest)

/**
 * The start dragged to [day], held on or before the last day. A start the window cannot reach the last day
 * from is not taken; an end time it would no longer come after is cleared, to be chosen again.
 */
internal fun EventRange.dragStartTo(day: LocalDate, latest: LatestUntil): EventRange {
    val moved = copy(from = LocalDateTime(minOf(day, endDay), from.time))
    return if (endDay <= latest.of(moved.from).date) moved.keepValidTime(latest) else this
}

/**
 * A sweep from [anchor] (where the long press landed) to [day]: a new range over the days between them, the
 * last day placed and its time blank.
 */
internal fun EventRange.sweep(anchor: LocalDate, day: LocalDate, latest: LatestUntil): EventRange =
    EventRange(from = LocalDateTime(minOf(anchor, day), from.time)).placeEnd(maxOf(anchor, day), latest)

/** The last day the calendar offers while the last day is pending; `null` when any day may be tapped. */
internal fun EventRange.lastPickableDay(latest: LatestUntil): LocalDate? =
    if (endPending) latest.of(from).date else null

private fun EventRange.placeEnd(day: LocalDate, latest: LatestUntil): EventRange =
    copy(endDay = day.coerceIn(from.date, latest.of(from).date), endPending = false).keepValidTime(latest)

/** The start moved earlier: pull the last day back inside the window if the move left it outside. */
private fun EventRange.withinWindow(latest: LatestUntil): EventRange =
    copy(endDay = endDay.coerceIn(from.date, latest.of(from).date)).keepValidTime(latest)

private fun EventRange.keepValidTime(latest: LatestUntil): EventRange =
    copy(untilTime = untilTime?.takeIf { untilAllowed(it, latest) })
