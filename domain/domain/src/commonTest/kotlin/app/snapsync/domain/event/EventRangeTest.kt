package app.snapsync.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class EventRangeTest {

    private val start = EventStart(ts("2026-07-14T18:00:00Z"))

    @Test
    fun `an event may be one instant, and an existing one longer than a new one may be`() {
        assertIs<EventRangeVerdict.Ok>(EventRange.of(start, EventEnd(start.at)))
        val longer = EventEnd(stamp(start.at.at + EventWindow.MAX + 1.seconds))
        assertIs<EventRangeVerdict.Ok>(EventRange.of(start, longer))
    }

    @Test
    fun `an event ends no earlier than it starts`() {
        assertEquals(EventRangeVerdict.Inverted, EventRange.of(start, EventEnd(ts("2026-07-14T17:59:59Z"))))
    }

    @Test
    fun `a moment is before, during or after the event, its ends inclusive`() {
        val range = anEvent().range
        assertEquals(EventPhase.Upcoming, range.phaseAt(ts("2026-07-14T17:59:59Z")))
        assertEquals(EventPhase.Running, range.phaseAt(ts("2026-07-14T18:00:00Z")))
        assertEquals(EventPhase.Running, range.phaseAt(ts("2026-07-21T18:00:00Z")))
        assertEquals(EventPhase.Ended, range.phaseAt(ts("2026-07-21T18:00:01Z")))
    }

    @Test
    fun `the members the event still waits for are the server's count, or those not settled`() {
        assertEquals(2, MemberCounts(active = 5, settled = 1, waiting = 2).waitingFor)
        assertEquals(4, MemberCounts(active = 5, settled = 1).waitingFor)
        assertEquals(0, MemberCounts(active = 1, settled = 3).waitingFor)
    }

    private fun stamp(instant: kotlin.time.Instant) = app.snapsync.domain.time.Timestamp.of(instant)
}
