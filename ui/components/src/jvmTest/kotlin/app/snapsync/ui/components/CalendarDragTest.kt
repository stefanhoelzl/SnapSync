package app.snapsync.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.snapsync.model.PickerView
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dragging on the range calendar (capabilities `create-event`, `join-event`): an end is dragged straight away, a
 * one-day range's first movement decides which end moves, a sweep needs a long press, and a finger off the days —
 * past the grid's edges or over a blank cell — reports no day. Each is reported as the gesture it was; what it makes is
 * the app's.
 *
 * March 2026 opens on a Sunday, so its grid is six Monday-first weeks with six blank cells before the 1st and five
 * after the 31st.
 */
class CalendarDragTest {

    private companion object {
        fun march(day: Int): LocalDate = LocalDate(2026, 3, day)

        /** A placed range over March [from]..[to], its end time blank. */
        fun placed(
            from: Int,
            to: Int,
        ) = picker(LocalDateTime(2026, 3, from, 9, 0), endDay = march(to), endPending = false)

        /** A fresh one-day range on the 10th, its last day pending. */
        val FRESH = picker(LocalDateTime(2026, 3, 10, 9, 0))
    }

    @get:Rule
    val rule = createComposeRule()

    private val log = GestureLog()

    @Test
    fun `dragging the start reports the start`() {
        setPicker(placed(10, 12))
        drag("Tuesday, 10 March 2026", back = true) { cells(-1, 0) }
        assertEquals("start ${march(9)}", log.last("start"))
        assertEquals(emptyList(), log.all("end"))
    }

    @Test
    fun `on a one-day range a drag back reports the start`() {
        setPicker(FRESH)
        drag("Tuesday, 10 March 2026", back = true) { cells(-1, 0) }
        assertEquals("start ${march(9)}", log.last("start"))
        assertEquals(emptyList(), log.all("end"))
    }

    @Test
    fun `on a one-day range a drag forward reports the last day`() {
        setPicker(FRESH)
        drag("Tuesday, 10 March 2026") { cells(1, 0) }
        assertEquals("end ${march(11)}", log.last("end"))
        assertEquals(emptyList(), log.all("start"))
    }

    @Test
    fun `a quick flick that jumps a day and lifts lands on that day`() {
        setPicker(placed(10, 12))
        rule.onNodeWithContentDescription("Thursday, 12 March 2026").performTouchInput {
            down(center)
            cells(2, 0) // one event, past the slop and onto Saturday the 14th
            up()
        }
        rule.waitForIdle()
        assertEquals("end ${march(14)}", log.last("end"))
    }

    @Test
    fun `a tap on an end without moving is a tap on that day`() {
        setPicker(FRESH)
        rule.onNodeWithContentDescription("Tuesday, 10 March 2026").performClick()
        rule.waitForIdle()
        assertEquals(listOf("day ${march(10)}"), log.events)
    }

    @Test
    fun `an end dragged past the grid's right edge stays on the last day it crossed`() {
        setPicker(placed(10, 12))
        drag("Thursday, 12 March 2026") {
            cells(1, 0)
            cells(1, 0)
            cells(1, 0) // Sunday, the last column
            cells(2, 0) // off the grid
        }
        assertEquals("end ${march(15)}", log.last("end"))
    }

    @Test
    fun `a start dragged past the grid's left or top edge stays on the last day it crossed`() {
        setPicker(placed(10, 12))
        drag("Tuesday, 10 March 2026", back = true) {
            cells(-1, 0) // Monday, the first column
            cells(0, -3) // above the grid
            cells(-1, 0) // and left of it
        }
        assertEquals("start ${march(9)}", log.last("start"))
    }

    @Test
    fun `an end dragged onto the blank cells after the month, or below the grid, reports no other day`() {
        setPicker(placed(10, 26))
        drag("Thursday, 26 March 2026") {
            cells(0, 1) // the blank under the 26th
            cells(0, 1) // below the last week
        }
        assertTrue(log.all("end").all { it == "end ${march(26)}" }, "${log.events}")
    }

    @Test
    fun `a start dragged onto the blank cells before the month reports no other day`() {
        setPicker(placed(3, 12))
        drag("Tuesday, 3 March 2026", back = true) { cells(0, -1) }
        assertTrue(log.all("start").all { it == "start ${march(3)}" }, "${log.events}")
    }

    @Test
    fun `a long press on a blank cell reports nothing`() {
        setPicker(placed(10, 12))
        rule.onNodeWithContentDescription("Sunday, 1 March 2026").performTouchInput {
            down(center - Offset(width.toFloat(), 0f)) // the Saturday before the 1st: blank
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            cells(1, 0)
            up()
        }
        rule.waitForIdle()
        assertEquals(emptyList(), log.events)
    }

    @Test
    fun `a sweep over a blank cell keeps the days it crossed`() {
        setPicker(FRESH)
        rule.onNodeWithContentDescription("Monday, 16 March 2026").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            cells(2, 0) // Wednesday the 18th
            cells(1, 2) // the blank two weeks on, past the 31st
            up()
        }
        rule.waitForIdle()
        assertEquals("sweep ${march(16)} ${march(18)}", log.last("sweep"))
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

    private fun setPicker(picker: PickerView) {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true, LocalDateFormats provides dateFormats(null)) {
                AppEventRangePicker(picker = picker, note = "note", gestures = log.gestures)
            }
        }
    }
}
