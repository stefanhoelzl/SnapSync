package app.snapsync.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

class CutoffTest {

    private fun clockAt(iso: String) = object : Clock {
        override fun now(): Instant = Instant.parse(iso)
    }

    @Test
    fun `now formats to the exact UTC Z shape`() {
        assertEquals("2026-07-06T14:32:11Z", nowCutoff(clockAt("2026-07-06T14:32:11Z")))
    }

    @Test
    fun `now truncates to whole seconds with no fractional part`() {
        assertEquals("2026-07-06T14:32:11Z", nowCutoff(clockAt("2026-07-06T14:32:11.987654Z")))
    }

    @Test
    fun `all fields are zero-padded to a fixed width`() {
        assertEquals("2026-01-02T03:04:05Z", nowCutoff(clockAt("2026-01-02T03:04:05Z")))
    }

    @Test
    fun `a fetched createdAt is already the cutoff shape and is reused verbatim`() {
        val createdAt = "2026-07-04T18:00:00Z"
        assertEquals(createdAt, instantToCutoff(Instant.parse(createdAt)))
    }

    @Test
    fun `a local pick converts to the corresponding UTC cutoff`() {
        // 16:32:11 local in CEST (UTC+2, July) is 14:32:11 UTC.
        val local = LocalDateTime(2026, 7, 6, 16, 32, 11)
        assertEquals("2026-07-06T14:32:11Z", localToCutoff(local, TimeZone.of("Europe/Berlin")))
    }

    @Test
    fun `the compare against creationDate is a lexicographic at-or-after`() {
        val cutoff = "2026-07-06T14:32:11Z"
        assertTrue("2026-07-06T14:32:12Z" >= cutoff, "strictly after is in scope")
        assertTrue("2026-07-06T14:32:11Z" >= cutoff, "equal instant is in scope")
        assertTrue("2026-07-06T14:32:10Z" < cutoff, "strictly before is out of scope")
        assertTrue("" < cutoff, "an undated asset (empty creationDate) is out of scope")
    }
}
