package app.snapsync.presentation

import app.snapsync.model.EVENT_WINDOW_MAX_SECONDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/**
 * The create screen's range limit (capability `create-event`: no range longer than 30 days). The bound
 * is the deployment's own `eventWindowMaxSeconds`, and it is measured the way the backend measures it —
 * in seconds between the two UTC instants the create sends — not in wall-clock days.
 */
class CutoffFormatterWindowTest {

    private val now = { Instant.parse("2026-07-14T18:00:00Z") }

    @Test
    fun `the generated limit is the 30 days the specs promise`() {
        assertEquals(30.days.inWholeSeconds, EVENT_WINDOW_MAX_SECONDS)
    }

    @Test
    fun `the latest end is exactly the window after the start`() {
        val f = CutoffFormatter(now, TimeZone.UTC)
        val from = LocalDateTime(2026, 7, 14, 18, 0)
        assertEquals(LocalDateTime(2026, 8, 13, 18, 0), f.latestEnd(from))
        assertTrue(f.fitsEventWindow(from, LocalDateTime(2026, 8, 13, 18, 0)))
        assertFalse(f.fitsEventWindow(from, LocalDateTime(2026, 8, 13, 18, 1)))
    }

    @Test
    fun `across a daylight-saving change the limit is in instants and not wall-clock days`() {
        // Berlin falls back on 25 Oct 2026, so 30 wall-clock days from 10 Oct 12:00 are 30 days and an HOUR
        // of real time — which the backend refuses. The latest end is therefore 11:00 on the wall clock.
        val f = CutoffFormatter(now, TimeZone.of("Europe/Berlin"))
        val from = LocalDateTime(2026, 10, 10, 12, 0)
        assertEquals(LocalDateTime(2026, 11, 9, 11, 0), f.latestEnd(from))
        assertTrue(f.fitsEventWindow(from, LocalDateTime(2026, 11, 9, 11, 0)))
        assertFalse(f.fitsEventWindow(from, LocalDateTime(2026, 11, 9, 12, 0)))
    }
}
