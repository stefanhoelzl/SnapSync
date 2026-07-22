package app.snapsync.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/**
 * The range + duration formatting the create/join surfaces render (capability `photo-selection-policy`):
 * compact-adaptive `formatRange` and humanized `humanizedDuration`. Zone-injected and pure.
 */
class CutoffFormatterRangeTest {

    private val f = CutoffFormatter(now = { Instant.parse("2026-07-14T18:00:00Z") }, zone = TimeZone.UTC)

    @Test
    fun `a same-day range collapses to one day with a time span`() {
        assertEquals(
            "14 Jul, 18:00–23:00",
            f.formatRange(LocalDateTime(2026, 7, 14, 18, 0), LocalDateTime(2026, 7, 14, 23, 0)),
        )
    }

    @Test
    fun `a multi-day whole-day range shows just the day span`() {
        assertEquals(
            "14–21 Jul 2026",
            f.formatRange(LocalDateTime(2026, 7, 14, 0, 0), LocalDateTime(2026, 7, 21, 0, 0)),
        )
    }

    @Test
    fun `a multi-day range with times shows both endpoints in full`() {
        assertEquals(
            "14 Jul 18:00 – 21 Jul 23:00",
            f.formatRange(LocalDateTime(2026, 7, 14, 18, 0), LocalDateTime(2026, 7, 21, 23, 0)),
        )
    }

    @Test
    fun `duration is a humanized coarse unit`() {
        assertEquals("1 day", f.humanizedDuration(LocalDateTime(2026, 7, 14, 18, 0), LocalDateTime(2026, 7, 15, 18, 0)))
        assertEquals("5 days", f.humanizedDuration(LocalDateTime(2026, 7, 14, 18, 0), LocalDateTime(2026, 7, 19, 18, 0)))
        assertEquals("2 weeks", f.humanizedDuration(LocalDateTime(2026, 7, 14, 0, 0), LocalDateTime(2026, 7, 29, 0, 0)))
        assertEquals("3 hours", f.humanizedDuration(LocalDateTime(2026, 7, 14, 18, 0), LocalDateTime(2026, 7, 14, 21, 0)))
    }
}
