package app.snapsync.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/**
 * The coarse duration the create surface states (capability `create-event`). Zone-injected and
 * pure, and still a formatter concern rather than a design-system one: it reads the injected clock's zone
 * to compare two instants, which is exactly what `appRangeLabel` does not do.
 */
class CutoffFormatterDurationTest {

    private val f = CutoffFormatter(now = { Instant.parse("2026-07-14T18:00:00Z") }, zone = TimeZone.UTC)

    @Test
    fun `duration is its coarsest whole unit`() {
        assertEquals(CoarseDuration.Days(1), f.coarseDuration(LocalDateTime(2026, 7, 14, 18, 0), LocalDateTime(2026, 7, 15, 18, 0)))
        assertEquals(CoarseDuration.Days(5), f.coarseDuration(LocalDateTime(2026, 7, 14, 18, 0), LocalDateTime(2026, 7, 19, 18, 0)))
        assertEquals(CoarseDuration.Weeks(2), f.coarseDuration(LocalDateTime(2026, 7, 14, 0, 0), LocalDateTime(2026, 7, 29, 0, 0)))
        assertEquals(CoarseDuration.Hours(3), f.coarseDuration(LocalDateTime(2026, 7, 14, 18, 0), LocalDateTime(2026, 7, 14, 21, 0)))
    }
}
