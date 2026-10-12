package app.snapsync.presentation.range

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

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
        val THIRTY_DAYS = RangeBounds.lastingAtMost { from ->
            LocalDateTime(
                from.date.plus(30, DateTimeUnit.DAY),
                from.time,
            )
        }

        /**
         * A join's bounds around the event, 20 Jul 18:00 – 25 Jul 18:00: at most 30 days, overlapping the event, its
         * end time always set.
         */
        val EVENT = RangeBounds.aroundEvent(
            LocalDateTime(2026, 7, 20, 18, 0),
            LocalDateTime(2026, 7, 25, 18, 0),
            longest = { from -> LocalDateTime(from.date.plus(30, DateTimeUnit.DAY), from.time) },
            earliestFrom = { until -> LocalDateTime(until.date.plus(-30, DateTimeUnit.DAY), until.time) },
        )
        val EVENT_START =
            EventRange(LocalDateTime(2026, 7, 20, 18, 0), LocalDate(2026, 7, 25), LocalTime(18, 0), endPending = false)

        /** The clock's hour, as the create screen hands it to a minute settle that has no hour yet. */
        const val NOW_HOUR = 18
    }

    /** Both end wheels settled: the hour, then the minute. */
    private fun EventRange.endAt(hour: Int, minute: Int, bounds: RangeBounds = THIRTY_DAYS) =
        settleUntilHour(hour, bounds).settleUntilMinute(minute, bounds, NOW_HOUR)

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
        val complete = fresh.pickDay(SUNDAY, THIRTY_DAYS).endAt(21, 0)
        val restarted = complete.pickDay(LocalDate(2026, 7, 10), THIRTY_DAYS)
        assertEquals(LocalDateTime(2026, 7, 10, 18, 4), restarted.from)
        assertEquals(LocalDate(2026, 7, 10), restarted.endDay)
        assertNull(restarted.untilTime)
        assertTrue(restarted.endPending)
    }

    @Test
    fun `dragging the last day moves it and keeps a time that is still valid`() {
        val set = fresh.pickDay(SUNDAY, THIRTY_DAYS).endAt(21, 0)
        val dragged = set.dragEndTo(LocalDate(2026, 7, 12), THIRTY_DAYS)
        assertEquals(LocalDateTime(2026, 7, 12, 21, 0), dragged.until)
        assertEquals(TODAY, dragged.dragEndTo(LocalDate(2026, 7, 1), THIRTY_DAYS).endDay, "never before the start")
        assertEquals(LocalDate(2026, 8, 5), dragged.dragEndTo(LocalDate(2026, 9, 1), THIRTY_DAYS).endDay)
    }

    @Test
    fun `dragging the start stops at the last day and clears an end time it would pass`() {
        val set = fresh.pickDay(SUNDAY, THIRTY_DAYS).endAt(10, 0)
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
    fun `settling the Until hour alone does not fill the minute or set the end`() {
        val set = fresh.pickDay(SUNDAY, THIRTY_DAYS).settleUntilHour(21, THIRTY_DAYS)
        assertEquals(21, set.untilHour)
        assertNull(set.untilMinute, "choosing the hour must never pre-fill the minute")
        assertNull(set.until)
    }

    @Test
    fun `settling the Until minute with the hour blank takes the clock's hour`() {
        val set = fresh.pickDay(SUNDAY, THIRTY_DAYS).settleUntilMinute(30, THIRTY_DAYS, currentHour = 20)
        assertEquals(LocalDateTime(2026, 7, 8, 20, 30), set.until)
    }

    @Test
    fun `a minute drag fills a blank hour with the clock's hour and leaves the minute blank`() {
        val filled = fresh.pickDay(SUNDAY, THIRTY_DAYS).fillUntilHour(20, THIRTY_DAYS)
        assertEquals(20, filled.untilHour)
        assertNull(filled.untilMinute)
        assertNull(filled.until)
        assertEquals(filled, filled.fillUntilHour(9, THIRTY_DAYS), "an hour already chosen is kept")
    }

    @Test
    fun `a clock hour that is no valid end fills the start's hour instead`() {
        // Same day, start 18:04, the clock at 16: 16 and 17 have no minute after the start, 18 does.
        val sameDay = fresh.pickDay(TODAY, THIRTY_DAYS)
        assertEquals(18, sameDay.fillUntilHour(16, THIRTY_DAYS).untilHour)
        // On the thirtieth day the latest end is 18:04: a clock at 22 lands on the start's hour too.
        assertEquals(18, fresh.pickDay(LocalDate(2026, 8, 5), THIRTY_DAYS).fillUntilHour(22, THIRTY_DAYS).untilHour)
    }

    @Test
    fun `hours and minutes settle independently`() {
        val set = fresh.pickDay(SUNDAY, THIRTY_DAYS).endAt(21, 30).settleUntilHour(22, THIRTY_DAYS)
        assertEquals(LocalDateTime(2026, 7, 8, 22, 30), set.until)
    }

    @Test
    fun `on a same-day range an end at or before the start is unreachable`() {
        val sameDay = fresh.pickDay(TODAY, THIRTY_DAYS)
        // 17:00 has no minute after 18:04, so the hour moves to the nearest one that does, and :00 in that
        // hour is still before the start, so the minutes move to the first valid one.
        assertEquals(18, sameDay.settleUntilHour(17, THIRTY_DAYS).untilHour)
        assertEquals(LocalTime(18, 5), sameDay.endAt(17, 0).untilTime)
        assertEquals(LocalTime(18, 5), sameDay.settleUntilMinute(4, THIRTY_DAYS, NOW_HOUR).untilTime)
        assertFalse(sameDay.untilAllowed(LocalTime(18, 4), THIRTY_DAYS))
        assertTrue(sameDay.untilAllowed(LocalTime(18, 5), THIRTY_DAYS))
    }

    @Test
    fun `a same-day end at 23 is accepted`() {
        assertEquals(LocalDateTime(2026, 7, 6, 23, 0), fresh.pickDay(TODAY, THIRTY_DAYS).endAt(23, 0).until)
    }

    @Test
    fun `while the last day is picked the calendar stops at 30 days`() {
        assertEquals(LocalDate(2026, 8, 5), fresh.lastPickableDay(THIRTY_DAYS))
        assertNull(
            fresh.pickDay(SUNDAY, THIRTY_DAYS).lastPickableDay(THIRTY_DAYS),
            "the next tap starts over, anywhere",
        )
    }

    @Test
    fun `on the thirtieth day an end past the window is pulled back to it`() {
        // 18:04 is the last end the window allows; 23 has no valid minute, so the hour moves to 18, where
        // :00 is still inside it.
        val last = fresh.pickDay(LocalDate(2026, 8, 5), THIRTY_DAYS).endAt(23, 0)
        assertEquals(LocalDateTime(2026, 8, 5, 18, 0), last.until)
        assertFalse(last.untilAllowed(LocalTime(18, 5), THIRTY_DAYS))
    }

    @Test
    fun `the start cannot be moved to or past a chosen same-day end`() {
        val range = fresh.pickDay(TODAY, THIRTY_DAYS).endAt(20, 0)
        assertEquals(LocalDateTime(2026, 7, 6, 19, 4), range.settleFromHour(21, THIRTY_DAYS).from)
        assertEquals(LocalDateTime(2026, 7, 6, 12, 4), range.settleFromHour(12, THIRTY_DAYS).from)
    }

    @Test
    fun `with no valid end on a same-day range the end stays blank`() {
        val late = EventRange(from = LocalDateTime(2026, 7, 6, 23, 59))
        assertNull(late.settleUntilHour(23, THIRTY_DAYS).untilHour)
    }

    // ---- around an event's window (join and settings) ----

    @Test
    fun `a join range opens complete and valid`() {
        assertTrue(EVENT_START.isValid(EVENT))
    }

    @Test
    fun `a first tap starts a new range and keeps the end time`() {
        val restarted = EVENT_START.pickDay(LocalDate(2026, 7, 22), EVENT)
        assertEquals(LocalDateTime(2026, 7, 22, 18, 0), restarted.from)
        assertEquals(LocalTime(18, 0), restarted.untilTime, "the end time is never blanked on join")
        assertFalse(restarted.isValid(EVENT), "until the last day is placed the range ends where it starts")
        val placed = restarted.pickDay(LocalDate(2026, 7, 23), EVENT)
        assertEquals(LocalDateTime(2026, 7, 23, 18, 0), placed.until)
        assertTrue(placed.isValid(EVENT))
    }

    @Test
    fun `a same-day join range moves the end time to the nearest valid one`() {
        val sameDay = EVENT_START.pickDay(LocalDate(2026, 7, 22), EVENT).pickDay(LocalDate(2026, 7, 22), EVENT)
        assertEquals(LocalDateTime(2026, 7, 22, 18, 1), sameDay.until)
    }

    @Test
    fun `dragging may reach outside the event — never past thirty days`() {
        // Capability `join-event`: the evening before, the morning after.
        assertEquals(LocalDate(2026, 7, 23), EVENT_START.dragEndTo(LocalDate(2026, 7, 23), EVENT).endDay)
        assertEquals(LocalDate(2026, 7, 30), EVENT_START.dragEndTo(LocalDate(2026, 7, 30), EVENT).endDay)
        assertEquals(LocalDate(2026, 8, 19), EVENT_START.dragEndTo(LocalDate(2026, 9, 30), EVENT).endDay, "30 days")
        val start = EVENT_START.dragStartTo(LocalDate(2026, 7, 10), EVENT)
        assertEquals(LocalDateTime(2026, 7, 10, 18, 0), start.from, "before the event's start")
        assertTrue(start.isValid(EVENT))
    }

    @Test
    fun `a start may be settled before the event's start`() {
        assertTrue(EVENT_START.fromAllowed(LocalTime(17, 59), EVENT))
        assertEquals(LocalDateTime(2026, 7, 20, 9, 0), EVENT_START.settleFromHour(9, EVENT).from)
    }

    @Test
    fun `no new range starts after the event — nor further back than thirty days before it`() {
        assertEquals(LocalDate(2026, 7, 25), EVENT_START.lastPickableDay(EVENT))
        assertEquals(LocalDate(2026, 6, 20), EVENT_START.view(EVENT, LocalDate(2026, 7, 1)).firstDay)
        assertEquals(
            LocalDateTime(2026, 7, 25, 18, 0),
            EVENT_START.pickDay(LocalDate(2026, 7, 28), EVENT).from,
            "a tap after the event starts the new range on the event's last moment",
        )
    }

    @Test
    fun `a range started before the event must end in it - the days between are not offered`() {
        val early = EVENT_START.pickDay(LocalDate(2026, 7, 10), EVENT)
        assertEquals(LocalDate(2026, 7, 20), early.firstEndDay(EVENT), "an end before the event's start would miss it")
        val placed = early.pickDay(LocalDate(2026, 7, 15), EVENT)
        assertEquals(LocalDate(2026, 7, 20), placed.endDay, "a tap in the gap is held to the event's first day")
        assertTrue(placed.isValid(EVENT))
        assertNull(EVENT_START.pickDay(LocalDate(2026, 7, 22), EVENT).firstEndDay(EVENT), "no gap inside the event")
    }

    @Test
    fun `the event's own days are marked`() {
        val view = EVENT_START.view(EVENT, LocalDate(2026, 7, 1))
        assertEquals(LocalDate(2026, 7, 20), view.markedDays?.first)
        assertEquals(LocalDate(2026, 7, 25), view.markedDays?.last)
        assertNull(fresh.view(THIRTY_DAYS, TODAY).markedDays, "the create screen marks none")
    }

    @Test
    fun `on join every settle keeps the end complete`() {
        assertEquals(LocalDateTime(2026, 7, 25, 17, 0), EVENT_START.settleUntilHour(17, EVENT).until)
        assertEquals(
            LocalDateTime(2026, 7, 25, 17, 30),
            EVENT_START.settleUntilHour(17, EVENT).settleUntilMinute(30, EVENT, NOW_HOUR).until,
        )
        assertEquals(
            LocalDateTime(2026, 7, 25, 18, 30),
            EVENT_START.settleUntilMinute(30, EVENT, NOW_HOUR).until,
            "an end after the event's is a member's choice",
        )
        assertTrue(EVENT_START.settleUntilHour(17, EVENT).isValid(EVENT))
    }

    @Test
    fun `an end hour chosen alone stays while one of its minutes is still a valid end`() {
        val hourOnly = fresh.pickDay(SUNDAY, THIRTY_DAYS).settleUntilHour(10, THIRTY_DAYS)
        assertEquals(10, hourOnly.dragEndTo(LocalDate(2026, 7, 9), THIRTY_DAYS).untilHour)
        assertNull(
            hourOnly.dragStartTo(LocalDate(2026, 7, 8), THIRTY_DAYS).untilHour,
            "10 on the start's day is before 18:04",
        )
        assertFalse(hourOnly.dragEndTo(LocalDate(2026, 7, 6), THIRTY_DAYS).untilHour != null)
    }

    // ---- a start the chosen end holds back ----

    @Test
    fun `on a thirty-day range the start cannot move earlier on its day`() {
        val month = fresh.pickDay(LocalDate(2026, 8, 5), THIRTY_DAYS).endAt(18, 4)
        assertFalse(month.fromAllowed(LocalTime(17, 0), THIRTY_DAYS), "the range would be longer than 30 days")
        assertEquals(START, month.settleFromHour(17, THIRTY_DAYS).from)
    }

    @Test
    fun `an end hour chosen alone holds the start between its minutes and the window`() {
        val sameDay = fresh.pickDay(TODAY, THIRTY_DAYS).settleUntilHour(19, THIRTY_DAYS)
        assertTrue(sameDay.fromAllowed(LocalTime(18, 30), THIRTY_DAYS))
        assertFalse(sameDay.fromAllowed(LocalTime(20, 0), THIRTY_DAYS), "no minute of 19 is after 20:00")
        val month = fresh.pickDay(LocalDate(2026, 8, 5), THIRTY_DAYS).settleUntilHour(18, THIRTY_DAYS)
        assertFalse(month.fromAllowed(LocalTime(17, 0), THIRTY_DAYS), "every minute of 18 is past the window")
    }

    @Test
    fun `with the end time blank the start keeps the last day inside the window across a clock change`() {
        // The window is measured in instants, as the app measures it: on 25 Oct 2026 Berlin's clocks go back an
        // hour, so 30 days from 00:30 on 1 Oct end at 23:30 on the 30th.
        val berlin = TimeZone.of("Europe/Berlin")
        val instants = RangeBounds.lastingAtMost { from ->
            from.toInstant(berlin).plus(30.days).toLocalDateTime(berlin)
        }
        val range = EventRange(from = LocalDateTime(2026, 10, 1, 18, 4)).pickDay(LocalDate(2026, 10, 31), instants)
        assertEquals(LocalDate(2026, 10, 31), range.endDay)
        assertFalse(range.fromAllowed(LocalTime(0, 30), instants), "the 31st is out of reach from 00:30")
        assertTrue(range.fromAllowed(LocalTime(1, 30), instants))
    }

    @Test
    fun `a complete range that misses the event or outlasts thirty days is not valid`() {
        val beforeTheEvent = EventRange(
            LocalDateTime(2026, 7, 10, 18, 0),
            LocalDate(2026, 7, 20),
            LocalTime(17, 59),
            endPending = false,
        )
        assertFalse(beforeTheEvent.isValid(EVENT), "it ends a minute before the event starts")
        val tooLong = EventRange(
            LocalDateTime(2026, 7, 1, 18, 0),
            LocalDate(2026, 8, 1),
            LocalTime(18, 0),
            endPending = false,
        )
        assertFalse(tooLong.isValid(EVENT))
        val around = EventRange(LocalDateTime(2026, 7, 19, 9, 0), LocalDate(2026, 7, 26), LocalTime(12, 0), false)
        assertTrue(around.isValid(EVENT), "the day before to the day after")
        assertFalse(fresh.isValid(THIRTY_DAYS), "an end time still blank is no range yet")
    }

    @Test
    fun `with no valid end on a same-day range the minute wheel fills nothing`() {
        val late = EventRange(from = LocalDateTime(2026, 7, 6, 23, 59))
        assertEquals(late, late.fillUntilHour(NOW_HOUR, THIRTY_DAYS))
        assertEquals(late, late.settleUntilMinute(30, THIRTY_DAYS, NOW_HOUR))
    }

    @Test
    fun `settling the From minute keeps the start's hour`() {
        assertEquals(LocalDateTime(2026, 7, 6, 18, 30), fresh.settleFromMinute(30, THIRTY_DAYS).from)
    }

    @Test
    fun `a join range restarted on the event's last day may end after the event`() {
        // Until its last day is placed the new range ends where it starts: 18:00 on the 25th, the event's end. An end
        // after it is a member's choice now, so the nearest valid end to 17:00 is the next minute.
        val lastDay = EVENT_START.pickDay(LocalDate(2026, 7, 25), EVENT)
        assertEquals(LocalDateTime(2026, 7, 25, 18, 1), lastDay.settleUntilHour(17, EVENT).until)
    }

    @Test
    fun `a join range restarted on the event's first day may start before the event`() {
        // Its end is 18:00 on the 20th, the event's start: a start at 19:00 would come after it, so the nearest valid
        // start is the hour before — before the event, which a member may choose.
        val firstDay = EVENT_START.pickDay(LocalDate(2026, 7, 20), EVENT)
        assertEquals(LocalDateTime(2026, 7, 20, 17, 0), firstDay.settleFromHour(19, EVENT).from)
    }

    // ---- the bounds around an event: a start held on both sides, and wheels with nothing to settle on ----

    @Test
    fun `a start further back than thirty days before the event cannot be settled`() {
        // The earliest start is 20 Jun 18:00 (30 days before the event's start): 17:59 on that day is out of reach.
        val farBack = EventRange(LocalDateTime(2026, 6, 20, 18, 0), LocalDate(2026, 7, 20), LocalTime(18, 0), false)
        assertTrue(farBack.isValid(EVENT))
        assertFalse(farBack.fromAllowed(LocalTime(17, 59), EVENT))
    }

    @Test
    fun `a start after the event's end cannot be settled`() {
        // On the event's last day a start after 18:00 would begin after the event ended.
        val lastDay = EventRange(LocalDateTime(2026, 7, 25, 17, 0), LocalDate(2026, 7, 27), LocalTime(12, 0), false)
        assertTrue(lastDay.fromAllowed(LocalTime(18, 0), EVENT))
        assertFalse(lastDay.fromAllowed(LocalTime(18, 1), EVENT))
    }

    @Test
    fun `an end wheel with no valid time keeps the range as it is`() {
        // The start is the day's last minute and the end lies on that same day: no end time comes after it.
        val late = EventRange(LocalDateTime(2026, 7, 6, 23, 59), LocalDate(2026, 7, 6), LocalTime(23, 59), false)
        assertEquals(late, late.settleUntilHour(10, THIRTY_DAYS))
    }

    @Test
    fun `a start wheel with no valid time keeps the range as it is`() {
        // The end is midnight on the start's day: no start time comes before it.
        val midnight = EventRange(LocalDateTime(2026, 7, 6, 0, 0), LocalDate(2026, 7, 6), LocalTime(0, 0), false)
        assertEquals(midnight, midnight.settleFromHour(9, THIRTY_DAYS))
    }
}
