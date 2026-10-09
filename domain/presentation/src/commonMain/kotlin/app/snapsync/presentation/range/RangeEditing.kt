package app.snapsync.presentation.range

import app.snapsync.ui.state.PickerView
import app.snapsync.ui.state.TimeSpan
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

// What a gesture on a range calendar does, and what the calendar is shown:
// the screen reports where it was touched; these rules decide what that makes, and what may be picked next.

/** A gesture on a range calendar, as the screen reported it. */
internal sealed interface RangeEdit {
    /** A tap on [day]. */
    data class Day(val day: LocalDate) : RangeEdit

    /** The start dragged to [day]. */
    data class DragStart(val day: LocalDate) : RangeEdit

    /** The last day dragged to [day]. */
    data class DragEnd(val day: LocalDate) : RangeEdit

    /** A long press on [anchor], swept to [day]. */
    data class Sweep(val anchor: LocalDate, val day: LocalDate) : RangeEdit

    /** A time wheel settled on [value]. */
    data class FromHour(val value: Int) : RangeEdit

    data class FromMinute(val value: Int) : RangeEdit

    data class UntilHour(val value: Int) : RangeEdit

    data class UntilMinute(val value: Int) : RangeEdit

    /** The end's minute wheel started moving while its hour is blank. */
    data object UntilMinuteDrag : RangeEdit
}

/** The range after [edit], held to [bounds]; a blank end hour fills from [currentHour] — the clock's. */
internal fun EventRange.after(edit: RangeEdit, bounds: RangeBounds, currentHour: Int): EventRange = when (edit) {
    is RangeEdit.Day -> pickDay(edit.day, bounds)
    is RangeEdit.DragStart -> dragStartTo(edit.day, bounds)
    is RangeEdit.DragEnd -> dragEndTo(edit.day, bounds)
    is RangeEdit.Sweep -> sweep(edit.anchor, edit.day, bounds)
    is RangeEdit.FromHour -> settleFromHour(edit.value, bounds)
    is RangeEdit.FromMinute -> settleFromMinute(edit.value, bounds)
    is RangeEdit.UntilHour -> settleUntilHour(edit.value, bounds)
    is RangeEdit.UntilMinute -> settleUntilMinute(edit.value, bounds, currentHour)
    RangeEdit.UntilMinuteDrag -> fillUntilHour(currentHour, bounds)
}

/** The range as the calendar draws it, with what may be picked next under [bounds]; [today] is the clock's date. */
internal fun EventRange.view(bounds: RangeBounds, today: LocalDate): PickerView = PickerView(
    from = from,
    endDay = endDay,
    untilHour = untilHour,
    untilMinute = untilMinute,
    endPending = endPending,
    today = today,
    firstDay = bounds.earliest?.date,
    lastDay = lastPickableDay(bounds),
    fromTimes = timeSpan { fromAllowed(it, bounds) },
    untilTimes = timeSpan { untilAllowed(it, bounds) },
    complete = isValid(bounds),
)

/**
 * The times of day [allowed] admits, as one span — they are contiguous by construction: every rule bounds a time from
 * below and above on one day — or `null` when it admits none.
 */
internal fun timeSpan(allowed: (LocalTime) -> Boolean): TimeSpan? {
    val times = (0 until MINUTES_PER_DAY).map { LocalTime(it / MINUTES_PER_HOUR, it % MINUTES_PER_HOUR) }
    val first = times.firstOrNull(allowed) ?: return null
    return TimeSpan(first, times.last(allowed))
}

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
