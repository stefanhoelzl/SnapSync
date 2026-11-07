package app.snapsync.presentation

import app.snapsync.control.Verifies
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.testids.testEventId
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The joined screen's dates (capability `sync-status`, "The joined screen shows how long the event lasts"): every moment
 * the screen states is the device's, converted once here. How the label writes them is `AppDateRangeLabelTest`'s.
 */
class JoinedDatesTest {

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen shows how long the event lasts",
        scenario = "Dates in the device's timezone",
    )
    fun `an event starting at 22-00 UTC on 13 July begins on 14 July two hours east`() {
        val event = TestMembership(
            eventId = testEventId("11111111-1111-4111-8111-111111111111"),
            name = "Lake Weekend",
            minPhotoDate = CaptureCutoff(timestamp("2026-07-13T22:00:00Z")),
            startsAt = EventStart(timestamp("2026-07-13T22:00:00Z")),
            endsAt = EventEnd(timestamp("2026-07-15T22:00:00Z")),
            maxPhotoDate = CaptureCeiling(timestamp("2026-07-15T22:00:00Z")),
            deletesAt = DeletesAt(timestamp("2099-12-31T00:00:00Z")),
        )
        val shown = event.shown(zone = TimeZone.of("UTC+02:00"))
        assertEquals(LocalDateTime(2026, 7, 14, 0, 0), shown.startsAt)
        assertEquals(LocalDateTime(2026, 7, 16, 0, 0), shown.endsAt)
        assertEquals(
            LocalDateTime(2026, 7, 13, 22, 0),
            event.shown(zone = TimeZone.UTC).startsAt,
            "and in UTC, the 13th",
        )
    }
}
