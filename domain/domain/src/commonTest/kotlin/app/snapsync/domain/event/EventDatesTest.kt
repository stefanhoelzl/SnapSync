package app.snapsync.domain.event

import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.time.Timestamp
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Every role is its own type over one [Timestamp], and orders as its instant. */
class EventDatesTest {

    private val early = Timestamp.of(Instant.parse("2026-07-14T18:00:00Z"))
    private val late = Timestamp.of(Instant.parse("2026-07-21T18:00:00Z"))

    @Test
    fun `every role orders as its instant`() {
        assertTrue(EventStart(early) < EventStart(late))
        assertTrue(EventEnd(early) < EventEnd(late))
        assertTrue(OpenUntil(early) < OpenUntil(late))
        assertTrue(DeletesAt(early) < DeletesAt(late))
        assertTrue(CaptureCutoff(early) < CaptureCutoff(late))
        assertTrue(CaptureCeiling(early) < CaptureCeiling(late))
    }

    @Test
    fun `an event is at most thirty days long`() {
        assertTrue(EventWindow.MAX == 30.days)
    }
}
