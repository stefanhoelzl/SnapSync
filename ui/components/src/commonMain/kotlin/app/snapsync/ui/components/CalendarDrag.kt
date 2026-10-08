package app.snapsync.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputEventHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

// Dragging on the range calendar (capabilities `create-event`, `join-event`): a range endpoint is dragged straight
// away, and a sweep across days selects a new range after a LONG PRESS. The long press is deliberate — the
// calendar fills most of a small phone's form, so a drag that started anywhere on it and selected days would
// leave the host no place to scroll the form from.

/** Which end of the range a drag holds. */
private enum class Handle { START, END }

/**
 * The drag gestures over a [RangeCalendarGrid] showing [visibleMonth]. Each move hands [onChange] the next
 * range from the rules in `EventRangeDays.kt`; taps are left to the day cells.
 */
@Composable
internal fun Modifier.rangeDrags(
    visibleMonth: LocalDate,
    range: EventRange,
    bounds: RangeBounds,
    onChange: (EventRange) -> Unit,
): Modifier {
    val current by rememberUpdatedState(range)
    val emit by rememberUpdatedState(onChange)
    val drags = PointerInputEventHandler {
        val month = MonthLayout(visibleMonth)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pressed = month.dayAt(down.position, size) ?: return@awaitEachGesture
            val onEndpoint = pressed == current.from.date || pressed == current.endDay
            if (onEndpoint) {
                dragEndpoint(down.id, pressed, month, size, bounds, { current }) { emit(it) }
            } else {
                awaitLongPressOrCancellation(down.id)?.let { press ->
                    emit(current.sweep(pressed, pressed, bounds))
                    drag(press.id) { change ->
                        month.dayAt(change.position, size)?.let { emit(current.sweep(pressed, it, bounds)) }
                        change.consume()
                    }
                }
            }
        }
    }
    return pointerInput(visibleMonth, drags)
}

/**
 * Drag the endpoint pressed on. When both ends sit on the same day, the first movement decides which: back
 * (up or left) is the start, forward is the end.
 */
private suspend fun AwaitPointerEventScope.dragEndpoint(
    pointer: PointerId,
    pressed: LocalDate,
    month: MonthLayout,
    size: IntSize,
    bounds: RangeBounds,
    current: () -> EventRange,
    emit: (EventRange) -> Unit,
) {
    var firstMove = Offset.Zero
    val moved = awaitTouchSlopOrCancellation(pointer) { change, overSlop ->
        firstMove = overSlop
        change.consume()
    } ?: return
    val held = whichHandle(current(), pressed, firstMove)
    drag(moved.id) { change ->
        month.dayAt(change.position, size)?.let { day ->
            emit(if (held == Handle.START) current().dragStartTo(day, bounds) else current().dragEndTo(day, bounds))
        }
        change.consume()
    }
}

private fun whichHandle(range: EventRange, pressed: LocalDate, overSlop: Offset): Handle = when {
    range.from.date != range.endDay -> if (pressed == range.from.date) Handle.START else Handle.END
    overSlop.x + overSlop.y < 0 -> Handle.START
    else -> Handle.END
}

/** Where a month's days sit in the grid, as [RangeCalendarGrid] lays them out: Monday-first weeks. */
private class MonthLayout(visibleMonth: LocalDate) {
    private val first = LocalDate(visibleMonth.year, visibleMonth.month.ordinal.plus(1), 1)
    private val leading = first.dayOfWeek.ordinal
    private val days = first.daysUntil(first.plus(1L, DateTimeUnit.MONTH))
    private val rows = (leading + days + ROUND_UP_TO_WHOLE_WEEK) / DAYS_PER_WEEK

    /** The day under [position], or `null` over a blank cell or outside the grid. */
    fun dayAt(position: Offset, size: IntSize): LocalDate? {
        val col = (position.x / size.width * DAYS_PER_WEEK).toInt()
        val row = (position.y / size.height * rows).toInt()
        val number = row * DAYS_PER_WEEK + col - leading + 1
        val inside = position.x >= 0 && position.y >= 0 && col < DAYS_PER_WEEK && row < rows
        return if (inside && number in 1..days) first.plus(number - 1, DateTimeUnit.DAY) else null
    }
}
