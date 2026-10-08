package app.snapsync.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Dragging on the range calendar (capabilities `create-event`, `join-event`): an end is dragged straight away, a
 * one-day range's first movement decides which end moves, a sweep needs a long press, and a finger off the days —
 * past the grid's edges or over a blank cell — moves nothing.
 *
 * March 2026 opens on a Sunday, so its grid is six Monday-first weeks with six blank cells before the 1st and five
 * after the 31st. A long window keeps every drag here clear of the length limit.
 */
class CalendarDragTest {

    private companion object {
        val TWO_MONTHS = RangeBounds.lastingAtMost { from ->
            LocalDateTime(
                from.date.plus(60, DateTimeUnit.DAY),
                from.time,
            )
        }

        fun march(day: Int): LocalDate = LocalDate(2026, 3, day)

        /** A placed range over March [from]..[to], its end time blank. */
        fun placed(from: Int, to: Int) =
            EventRange(LocalDateTime(2026, 3, from, 9, 0), endDay = march(to), endPending = false)
    }

    @get:Rule
    val rule = createComposeRule()

    private var range by mutableStateOf(EventRange(from = LocalDateTime(2026, 3, 10, 9, 0)))

    @Test
    fun `dragging the start moves it`() {
        range = placed(10, 12)
        setPicker()
        drag("Tuesday, 10 March 2026", back = true) { cells(-1, 0) }
        assertEquals(march(9), range.from.date)
        assertEquals(march(12), range.endDay)
    }

    @Test
    fun `on a one-day range a drag back moves the start`() {
        setPicker()
        drag("Tuesday, 10 March 2026", back = true) { cells(-1, 0) }
        assertEquals(march(9), range.from.date)
        assertEquals(march(10), range.endDay)
    }

    @Test
    fun `on a one-day range a drag forward moves the last day`() {
        setPicker()
        drag("Tuesday, 10 March 2026") { cells(1, 0) }
        assertEquals(march(10), range.from.date)
        assertEquals(march(11), range.endDay)
    }

    @Test
    fun `a tap on an end without moving picks that day`() {
        setPicker()
        rule.onNodeWithContentDescription("Tuesday, 10 March 2026").performClick()
        rule.waitForIdle()
        assertFalse(range.endPending, "the tap placed the last day on the start's day")
        assertEquals(march(10), range.endDay)
    }

    @Test
    fun `an end dragged past the grid's right edge stays on the last day it crossed`() {
        range = placed(10, 12)
        setPicker()
        drag("Thursday, 12 March 2026") {
            cells(1, 0)
            cells(1, 0)
            cells(1, 0) // Sunday, the last column
            cells(2, 0) // off the grid
        }
        assertEquals(march(15), range.endDay)
    }

    @Test
    fun `a start dragged past the grid's left or top edge stays on the last day it crossed`() {
        range = placed(10, 12)
        setPicker()
        drag("Tuesday, 10 March 2026", back = true) {
            cells(-1, 0) // Monday, the first column
            cells(0, -3) // above the grid
            cells(-1, 0) // and left of it
        }
        assertEquals(march(9), range.from.date)
    }

    @Test
    fun `an end dragged onto the blank cells after the month, or below the grid, stays where it was`() {
        range = placed(10, 26)
        setPicker()
        drag("Thursday, 26 March 2026") {
            cells(0, 1) // the blank under the 26th
            cells(0, 1) // below the last week
        }
        assertEquals(march(26), range.endDay)
    }

    @Test
    fun `a start dragged onto the blank cells before the month stays where it was`() {
        range = placed(3, 12)
        setPicker()
        drag("Tuesday, 3 March 2026", back = true) { cells(0, -1) }
        assertEquals(march(3), range.from.date)
    }

    @Test
    fun `a long press on a blank cell selects nothing`() {
        range = placed(10, 12)
        setPicker()
        rule.onNodeWithContentDescription("Sunday, 1 March 2026").performTouchInput {
            down(center - Offset(width.toFloat(), 0f)) // the Saturday before the 1st: blank
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            cells(1, 0)
            up()
        }
        rule.waitForIdle()
        assertEquals(placed(10, 12), range)
    }

    @Test
    fun `a sweep over a blank cell keeps the days it crossed`() {
        setPicker()
        rule.onNodeWithContentDescription("Monday, 16 March 2026").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            cells(2, 0) // Wednesday the 18th
            cells(1, 2) // the blank two weeks on, past the 31st
            up()
        }
        rule.waitForIdle()
        assertEquals(march(16), range.from.date)
        assertEquals(march(18), range.endDay)
    }

    /**
     * A drag pressed on the day [description] names, moved by [moves], then lifted. It starts as a finger does: a
     * movement just past the touch slop, [back] (up and left) or forward, which picks the end without leaving the day.
     */
    private fun drag(description: String, back: Boolean = false, moves: TouchInjectionScope.() -> Unit) {
        rule.onNodeWithContentDescription(description).performTouchInput {
            down(center)
            val slop = (viewConfiguration.touchSlop + 2) * if (back) -1 else 1
            moveBy(Offset(slop, 0f))
            moves()
            up()
        }
        rule.waitForIdle()
    }

    /** The finger moved [columns] across and [rows] down, a day cell each. */
    private fun TouchInjectionScope.cells(columns: Int, rows: Int) =
        moveBy(Offset(width * columns.toFloat(), height * rows.toFloat()))

    private fun setPicker() {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                AppEventRangePicker(
                    range = range,
                    bounds = TWO_MONTHS,
                    note = "note",
                    currentHour = { 15 },
                    onChange = { range = it },
                )
            }
        }
    }
}
