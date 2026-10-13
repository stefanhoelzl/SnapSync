package app.snapsync.presentation

import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.model.TimestampText
import app.snapsync.ui.state.EventTiming
import app.snapsync.ui.state.TimeLeft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The joined screen's dates line (capability `sync-status`, "The joined screen shows how long the event
 * lasts"): whole days while a day or more remains, then hours, then minutes — floored, never weeks.
 */
class EventTimingTest {

    private val start = EventStart(TimestampText.parse("2026-07-12T14:00:00Z"))
    private val end = EventEnd(TimestampText.parse("2026-07-14T22:00:00Z"))

    private fun at(iso: String) = eventTiming(start, end, TimestampText.parse(iso))

    @Test
    fun `a span is floored to its largest whole unit`() {
        assertEquals(TimeLeft.Days(2), timeLeftOf(2.days + 5.hours))
        assertEquals(TimeLeft.Days(1), timeLeftOf(47.hours))
        assertEquals(TimeLeft.Days(29), timeLeftOf(29.days + 23.hours))
        assertEquals(TimeLeft.Hours(23), timeLeftOf(23.hours + 59.minutes))
        assertEquals(TimeLeft.Hours(1), timeLeftOf(1.hours))
        assertEquals(TimeLeft.Minutes(40), timeLeftOf(40.minutes + 30.seconds))
        assertEquals(TimeLeft.Minutes(1), timeLeftOf(1.minutes))
        assertEquals(TimeLeft.UnderAMinute, timeLeftOf(59.seconds))
    }

    @Test
    fun `before the start it counts down to the start`() {
        assertEquals(EventTiming.Upcoming(TimeLeft.Days(2)), at("2026-07-10T10:00:00Z"))
        assertEquals(EventTiming.Upcoming(TimeLeft.Hours(5)), at("2026-07-12T09:00:00Z"))
    }

    @Test
    fun `the start itself has begun`() {
        // The same inclusivity as the not-started health: `startsAt > now` is the only "not yet".
        assertEquals(EventTiming.Running(TimeLeft.Days(2)), at("2026-07-12T14:00:00Z"))
    }

    @Test
    fun `while it runs it counts down to the end`() {
        assertEquals(EventTiming.Running(TimeLeft.Days(2)), at("2026-07-12T17:00:00Z"))
        assertEquals(EventTiming.Running(TimeLeft.Hours(5)), at("2026-07-14T17:00:00Z"))
        assertEquals(EventTiming.Running(TimeLeft.Minutes(40)), at("2026-07-14T21:20:00Z"))
    }

    @Test
    fun `the end itself has not yet passed and a second later it has`() {
        assertEquals(EventTiming.Running(TimeLeft.UnderAMinute), at("2026-07-14T22:00:00Z"))
        assertEquals(EventTiming.Ended, at("2026-07-14T22:00:01Z"))
    }
}
