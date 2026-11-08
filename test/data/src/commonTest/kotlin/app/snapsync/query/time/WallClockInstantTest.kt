package app.snapsync.query.time

import app.snapsync.data.time.WallClock
import app.snapsync.mock.fixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The screen's current instant: the device's wall clock, to its own precision — between the seconds too. */
class WallClockInstantTest {
    @Test
    fun `the screen reads the wall clock's instant, never cut to the second`() {
        val at = Instant.parse("2026-07-09T12:00:00.250Z")

        assertEquals(at, WallClockInstant(WallClock(fixedClock(at))).now())
    }
}
