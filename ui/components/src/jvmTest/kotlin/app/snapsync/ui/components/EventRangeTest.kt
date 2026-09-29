package app.snapsync.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * The create screen's range rules (capability `create-event`): the end is chosen in two steps and neither
 * step is ever pre-filled, a bad range is unreachable rather than refused, and the 30-day window holds.
 */
class EventRangeTest {

    private companion object {
        val START = LocalDateTime(2026, 7, 6, 18, 4)
        val TODAY: LocalDate = START.date
        val SUNDAY = LocalDate(2026, 7, 8)

        /** The event window's length: 30 days from the start, as the app's formatter answers it. */
        val THIRTY_DAYS = RangeBounds.lastingAtMost { from -> LocalDateTime(from.date.plus(30, DateTimeUnit.DAY), from.time) }

        /** A join's window: the event, 20 Jul 18:00 – 25 Jul 18:00, its end time always set. */
        val EVENT = RangeBounds.within(LocalDateTime(2026, 7, 20, 18, 0), LocalDateTime(2026, 7, 25, 18, 0))
        val WHOLE_EVENT = EventRange(LocalDateTime(2026, 7, 20, 18, 0), LocalDate(2026, 7, 25), LocalTime(18, 0), endPending = false)
    }

    private val fresh = EventRange(from = START)

    @Test
    fun `a fresh range ends today with the end time blank`() {
        assertEquals(TODAY, fresh.endDay)
        assertNull(fresh.untilTime)
        assertNull(fresh.until)
        assertTrue(fresh.endPending)
    }

    @Test
    fun `the first tap on a later day places the last day and leaves its time blank`() {
        val picked = fresh.pickDay(SUNDAY, THIRTY_DAYS)
        assertEquals(START, picked.from)
        assertEquals(SUNDAY, picked.endDay)
        assertNull(picked.untilTime, "a day tap must never pre-fill the end time")
        assertFalse(picked.endPending)
    }

    @Test
    fun `while the last day is pending a day before the start moves the start and keeps its clock time`() {
        val moved = fresh.pickDay(LocalDate(2026, 6, 26), THIRTY_DAYS)
        assertEquals(LocalDateTime(2026, 6, 26, 18, 4), moved.from)
        assertEquals(TODAY, moved.endDay)
        assertTrue(moved.endPending, "the next tap still places the last day")
    }

    @Test
    fun `a start moved far back pulls the last day inside the window`() {
        val moved = fresh.pickDay(LocalDate(2026, 5, 1), THIRTY_DAYS)
        assertEquals(LocalDate(2026, 5, 31), moved.endDay)
    }

    @Test
    fun `once the last day is placed a tap starts a new range on that day`() {
        val complete = fresh.pickDay(SUNDAY, THIRTY_DAYS).settleUntilHour(21, THIRTY_DAYS)
        val restarted = complete.pickDay(LocalDate(2026, 7, 10), THIRTY_DAYS)
        assertEquals(LocalDateTime(2026, 7, 10, 18, 4), restarted.from)
        assertEquals(LocalDate(2026, 7, 10), restarted.endDay)
        assertNull(restarted.untilTime)
        assertTrue(restarted.endPending)
    }

    @Test
    fun `dragging the last day moves it and keeps a time that is still valid`() {
        val set = fresh.pickDay(SUNDAY, THIRTY_DAYS).settleUntilHour(21, THIRTY_DAYS)
        val dragged = set.dragEndTo(LocalDate(2026, 7, 12), THIRTY_DAYS)
        assertEquals(LocalDateTime(2026, 7, 12, 21, 0), dragged.until)
        assertEquals(TODAY, dragged.dragEndTo(LocalDate(2026, 7, 1), THIRTY_DAYS).endDay, "never before the start")
        assertEquals(LocalDate(2026, 8, 5), dragged.dragEndTo(LocalDate(2026, 9, 1), THIRTY_DAYS).endDay)
    }

    @Test
    fun `dragging the start stops at the last day and clears an end time it would pass`() {
        val set = fresh.pickDay(SUNDAY, THIRTY_DAYS).settleUntilHour(10, THIRTY_DAYS)
        val dragged = set.dragStartTo(LocalDate(2026, 7, 20), THIRTY_DAYS)
        assertEquals(LocalDateTime(2026, 7, 8, 18, 4), dragged.from)
        assertNull(dragged.untilTime, "10:00 on the start's own day would come before it")
        assertEquals(set, set.dragStartTo(LocalDate(2026, 5, 1), THIRTY_DAYS), "the window cannot reach Sunday")
    }

    @Test
    fun `a sweep selects the days between the press and the finger either way`() {
        val forward = fresh.sweep(LocalDate(2026, 7, 10), LocalDate(2026, 7, 12), THIRTY_DAYS)
        assertEquals(LocalDateTime(2026, 7, 10, 18, 4), forward.from)
        assertEquals(LocalDate(2026, 7, 12), forward.endDay)
        assertNull(forward.untilTime)
        assertFalse(forward.endPending)
        val backward = fresh.sweep(LocalDate(2026, 7, 12), LocalDate(2026, 7, 10), THIRTY_DAYS)
        assertEquals(forward, backward)
    }

    @Test
    fun `settling the Until hour from blank fills the minutes as zero`() {
        val set = fresh.pickDay(SUNDAY, THIRTY_DAYS).settleUntilHour(21, THIRTY_DAYS)
        assertEquals(LocalDateTime(2026, 7, 8, 21, 0), set.until)
    }

    @Test
    fun `settling the Until minute from blank takes the hour the wheel sat over`() {
        val set = fresh.pickDay(SUNDAY, THIRTY_DAYS).settleUntilMinute(30, THIRTY_DAYS)
        assertEquals(LocalDateTime(2026, 7, 8, 18, 30), set.until)
    }

    @Test
    fun `hours and minutes settle independently`() {
        val set = fresh.pickDay(SUNDAY, THIRTY_DAYS).settleUntilHour(21, THIRTY_DAYS).settleUntilMinute(30, THIRTY_DAYS)
            .settleUntilHour(22, THIRTY_DAYS)
        assertEquals(LocalDateTime(2026, 7, 8, 22, 30), set.until)
    }

    @Test
    fun `on a same-day range an end at or before the start is unreachable`() {
        val sameDay = fresh.pickDay(TODAY, THIRTY_DAYS)
        // 17:00 has no minute after 18:04, so the hour moves to the nearest one that does, and :00 in that
        // hour is still before the start, so the minutes move to the first valid one.
        assertEquals(LocalTime(18, 5), sameDay.settleUntilHour(17, THIRTY_DAYS).untilTime)
        assertEquals(LocalTime(18, 5), sameDay.settleUntilMinute(4, THIRTY_DAYS).untilTime)
        assertFalse(sameDay.untilAllowed(LocalTime(18, 4), THIRTY_DAYS))
        assertTrue(sameDay.untilAllowed(LocalTime(18, 5), THIRTY_DAYS))
    }

    @Test
    fun `a same-day end at 23 is accepted`() {
        assertEquals(LocalDateTime(2026, 7, 6, 23, 0), fresh.pickDay(TODAY, THIRTY_DAYS).settleUntilHour(23, THIRTY_DAYS).until)
    }

    @Test
    fun `while the last day is picked the calendar stops at 30 days`() {
        assertEquals(LocalDate(2026, 8, 5), fresh.lastPickableDay(THIRTY_DAYS))
        assertNull(fresh.pickDay(SUNDAY, THIRTY_DAYS).lastPickableDay(THIRTY_DAYS), "the next tap starts over, anywhere")
    }

    @Test
    fun `on the thirtieth day an end past the window is pulled back to it`() {
        // 18:04 is the last end the window allows; 23 has no valid minute, so the hour moves to 18, where
        // the blank end's :00 is still inside it.
        val last = fresh.pickDay(LocalDate(2026, 8, 5), THIRTY_DAYS).settleUntilHour(23, THIRTY_DAYS)
        assertEquals(LocalDateTime(2026, 8, 5, 18, 0), last.until)
        assertFalse(last.untilAllowed(LocalTime(18, 5), THIRTY_DAYS))
    }

    @Test
    fun `the start cannot be moved to or past a chosen same-day end`() {
        val range = fresh.pickDay(TODAY, THIRTY_DAYS).settleUntilHour(20, THIRTY_DAYS)
        assertEquals(LocalDateTime(2026, 7, 6, 19, 4), range.settleFromHour(21, THIRTY_DAYS).from)
        assertEquals(LocalDateTime(2026, 7, 6, 12, 4), range.settleFromHour(12, THIRTY_DAYS).from)
    }

    @Test
    fun `with no valid end on a same-day range the end stays blank`() {
        val late = EventRange(from = LocalDateTime(2026, 7, 6, 23, 59))
        assertNull(late.settleUntilHour(23, THIRTY_DAYS).untilTime)
    }

    // ---- inside an event's window (join and settings) ----

    @Test
    fun `a join range opens complete and valid`() {
        assertTrue(WHOLE_EVENT.isValid(EVENT))
    }

    @Test
    fun `a first tap starts a new range and keeps the end time`() {
        val restarted = WHOLE_EVENT.pickDay(LocalDate(2026, 7, 22), EVENT)
        assertEquals(LocalDateTime(2026, 7, 22, 18, 0), restarted.from)
        assertEquals(LocalTime(18, 0), restarted.untilTime, "the end time is never blanked on join")
        assertFalse(restarted.isValid(EVENT), "until the last day is placed the range ends where it starts")
        val placed = restarted.pickDay(LocalDate(2026, 7, 23), EVENT)
        assertEquals(LocalDateTime(2026, 7, 23, 18, 0), placed.until)
        assertTrue(placed.isValid(EVENT))
    }

    @Test
    fun `a same-day join range moves the end time to the nearest valid one`() {
        val sameDay = WHOLE_EVENT.pickDay(LocalDate(2026, 7, 22), EVENT).pickDay(LocalDate(2026, 7, 22), EVENT)
        assertEquals(LocalDateTime(2026, 7, 22, 18, 1), sameDay.until)
    }

    @Test
    fun `dragging an end narrows the range and never leaves the window`() {
        assertEquals(LocalDate(2026, 7, 23), WHOLE_EVENT.dragEndTo(LocalDate(2026, 7, 23), EVENT).endDay)
        assertEquals(LocalDate(2026, 7, 25), WHOLE_EVENT.dragEndTo(LocalDate(2026, 7, 30), EVENT).endDay)
        val start = WHOLE_EVENT.dragStartTo(LocalDate(2026, 7, 10), EVENT)
        assertEquals(LocalDateTime(2026, 7, 20, 18, 0), start.from, "never before the event's start")
    }

    @Test
    fun `on the window's first day a start before the event cannot be settled`() {
        assertFalse(WHOLE_EVENT.fromAllowed(LocalTime(17, 59), EVENT))
        assertEquals(LocalDateTime(2026, 7, 20, 18, 0), WHOLE_EVENT.settleFromHour(9, EVENT).from)
    }

    @Test
    fun `days past the event are never offered`() {
        assertEquals(LocalDate(2026, 7, 25), WHOLE_EVENT.lastPickableDay(EVENT))
    }
}
