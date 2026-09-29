package app.snapsync.ui.components

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

// What a tap or a drag on the calendar does to the range (capabilities `create-event`, `join-event`). Every
// result keeps the range inside its [RangeBounds] — the last day never before the start's day nor past the
// latest end, the start never before the earliest — so a bad range is unreachable rather than refused. The
// start's clock time is kept whenever its day moves.

/**
 * A tap on [day]. While the last day is pending, a day on or after the start places it, and a day before the
 * start moves the start there. Once the last day has been placed, the tap starts a new range at [day]: both
 * ends on that day and the last day pending again (see [restartAt] for its end time).
 */
internal fun EventRange.pickDay(day: LocalDate, bounds: RangeBounds): EventRange = when {
    !endPending -> restartAt(day, bounds)
    day < from.date -> copy(from = startOn(day, bounds)).withinWindow(bounds)
    else -> placeEnd(day, bounds)
}

/** The last day dragged to [day]: it is placed there, held between the start's day and the window's end. */
internal fun EventRange.dragEndTo(day: LocalDate, bounds: RangeBounds): EventRange = placeEnd(day, bounds)

/**
 * The start dragged to [day], held on or before the last day. A start the window cannot reach the last day
 * from is not taken; an end time it would no longer come after is cleared or moved (see [keepValidTime]).
 */
internal fun EventRange.dragStartTo(day: LocalDate, bounds: RangeBounds): EventRange {
    val moved = copy(from = startOn(minOf(day, endDay), bounds))
    return if (endDay <= bounds.latestEnd(moved.from).date) moved.keepValidTime(bounds) else this
}

/**
 * A sweep from [anchor] (where the long press landed) to [day]: a new range over the days between them, the
 * last day placed.
 */
internal fun EventRange.sweep(anchor: LocalDate, day: LocalDate, bounds: RangeBounds): EventRange =
    restartAt(minOf(anchor, day), bounds).placeEnd(maxOf(anchor, day), bounds)

/**
 * The last day the calendar offers: while the last day is pending, the latest end the start allows;
 * otherwise the window's end, or `null` when any day may start a new range.
 */
internal fun EventRange.lastPickableDay(bounds: RangeBounds): LocalDate? =
    if (endPending) bounds.latestEnd(from).date else bounds.ceiling?.date

/**
 * A new range on [day]: both ends there, the last day pending. The end time is blanked where the host must
 * set it; elsewhere it is kept, and placing the last day moves it to the nearest valid time.
 */
private fun EventRange.restartAt(day: LocalDate, bounds: RangeBounds): EventRange {
    val start = startOn(day, bounds)
    return EventRange(from = start, untilTime = untilTime.takeUnless { bounds.blankEndTime })
}

/** The start moved to [day] at its clock time, never before the earliest start. */
private fun EventRange.startOn(day: LocalDate, bounds: RangeBounds): LocalDateTime =
    LocalDateTime(day, from.time).let { start -> bounds.earliest?.let { maxOf(start, it) } ?: start }

private fun EventRange.placeEnd(day: LocalDate, bounds: RangeBounds): EventRange =
    copy(endDay = day.coerceIn(from.date, bounds.latestEnd(from).date), endPending = false).keepValidTime(bounds)

/** The start moved earlier: pull the last day back inside the window if the move left it outside. */
private fun EventRange.withinWindow(bounds: RangeBounds): EventRange =
    copy(endDay = endDay.coerceIn(from.date, bounds.latestEnd(from).date)).keepValidTime(bounds)

/**
 * The end time after a day moved: kept while it is still a valid end; otherwise cleared where the host sets
 * it, or moved to the nearest valid time where it is always set.
 */
private fun EventRange.keepValidTime(bounds: RangeBounds): EventRange {
    val time = untilTime ?: return this
    val kept = when {
        untilAllowed(time, bounds) -> time
        bounds.blankEndTime -> null
        else -> nearestTime(time.hour, time.minute) { untilAllowed(it, bounds) }
    }
    return copy(untilTime = kept)
}
