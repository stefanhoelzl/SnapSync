package app.snapsync.presentation

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.testids.testEventId
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** When the joined screen's clock-driven lines can next change. */
class NextClockChangeTest {

    private val clock = CutoffFormatter(now = { Instant.DISTANT_PAST }, zone = TimeZone.of("Europe/Berlin"))

    private val running = TestMembership(
        eventId = testEventId("11111111-1111-4111-8111-111111111111"),
        name = "Party",
        minPhotoDate = CaptureCutoff(timestamp("2026-07-06T00:00:00Z")),
        endsAt = EventEnd(timestamp("2026-07-10T00:00:00Z")),
        maxPhotoDate = CaptureCeiling(timestamp("2026-07-10T00:00:00Z")),
        deletesAt = DeletesAt(timestamp("2026-08-10T00:00:00Z")),
    )

    @Test
    fun `while the start or the end is ahead, every minute`() {
        val event = running.held().event
        assertEquals(
            timestamp("2026-07-05T12:01:00Z"),
            nextClockChange(event, timestamp("2026-07-05T12:00:00Z"), clock),
        )
        assertEquals(
            timestamp("2026-07-08T12:01:00Z"),
            nextClockChange(event, timestamp("2026-07-08T12:00:00Z"), clock),
        )
        assertEquals(
            timestamp("2026-07-10T00:01:00Z"),
            nextClockChange(event, timestamp("2026-07-10T00:00:00Z"), clock),
            "at the end itself, a minute on — when it has ended",
        )
    }

    @Test
    fun `once both have passed, the next local midnight`() {
        // 12:00 UTC on the 11th is 14:00 in Berlin: the next midnight there is 22:00 UTC.
        val event = running.held().event
        assertEquals(
            timestamp("2026-07-11T22:00:00Z"),
            nextClockChange(event, timestamp("2026-07-11T12:00:00Z"), clock),
        )
    }

    @Test
    fun `or the event's open-until date, when it comes first`() {
        val event = running.copy(openUntil = OpenUntil(timestamp("2026-07-11T18:00:00Z"))).held().event
        assertEquals(
            timestamp("2026-07-11T18:00:00Z"),
            nextClockChange(event, timestamp("2026-07-11T12:00:00Z"), clock),
        )
        assertEquals(
            timestamp("2026-07-11T22:00:00Z"),
            nextClockChange(event, timestamp("2026-07-11T19:00:00Z"), clock),
        )
        val late = running.copy(openUntil = OpenUntil(timestamp("2026-07-12T06:00:00Z"))).held().event
        assertEquals(timestamp("2026-07-11T22:00:00Z"), nextClockChange(late, timestamp("2026-07-11T12:00:00Z"), clock))
    }
}
