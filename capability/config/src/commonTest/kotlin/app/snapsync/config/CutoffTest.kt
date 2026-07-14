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

    // ── the event-start floor (capability `photo-selection-policy`) ─────────────────────────────────────

    private val startsAt = "2026-07-14T18:00:00Z"

    @Test
    fun `a cutoff below the event start is clamped up to it`() {
        assertEquals(startsAt, clampToFloor(chosen = "2026-07-14T12:00:00Z", startsAt = startsAt))
        assertEquals(startsAt, clampToFloor(chosen = "2001-01-01T00:00:00Z", startsAt = startsAt))
    }

    @Test
    fun `a cutoff at or above the event start is honored unchanged`() {
        assertEquals(startsAt, clampToFloor(chosen = startsAt, startsAt = startsAt))
        assertEquals(
            "2026-07-14T21:00:00Z",
            clampToFloor(chosen = "2026-07-14T21:00:00Z", startsAt = startsAt),
            "the member chooses freely ABOVE the floor",
        )
    }

    @Test
    fun `the floor only ever narrows scope`() {
        // The whole safety argument in one assertion: whatever the member (or a hostile deeplink) picks,
        // the persisted cutoff is never EARLIER than the event's start — so no photo taken before the
        // event began can be uploaded to it.
        val picks = listOf("", "2001-01-01T00:00:00Z", startsAt, "2099-12-31T23:59:59Z")
        for (chosen in picks) {
            assertTrue(
                clampToFloor(chosen, startsAt) >= startsAt,
                "clamp($chosen) must not fall below the floor",
            )
        }
    }

    @Test
    fun `the empty-string cutoff cannot survive the clamp`() {
        // "" is the trapdoor to whole-library scope (every string is >= ""). The floor closes it: even if
        // an empty cutoff reached the clamp, it is raised to the event's start.
        assertEquals(startsAt, clampToFloor(chosen = "", startsAt = startsAt))
    }

    @Test
    fun `a future event start clamps every choice into the future`() {
        // This is what makes "nothing syncs before the event starts" a theorem rather than a gate: a
        // photo's creationDate cannot be in the future, so a future cutoff admits nothing.
        val future = "2099-12-31T23:59:59Z"
        assertEquals(future, clampToFloor(chosen = "2026-07-14T12:00:00Z", startsAt = future))
        assertTrue("2026-07-14T12:00:00Z" < future, "no photo of today satisfies a 2099 cutoff")
    }
}
