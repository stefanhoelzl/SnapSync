package app.snapsync.model

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
        assertEquals(CaptureDate("2026-07-06T14:32:11Z"), nowCutoff(clockAt("2026-07-06T14:32:11Z")))
    }

    @Test
    fun `now truncates to whole seconds with no fractional part`() {
        assertEquals(CaptureDate("2026-07-06T14:32:11Z"), nowCutoff(clockAt("2026-07-06T14:32:11.987654Z")))
    }

    @Test
    fun `all fields are zero-padded to a fixed width`() {
        assertEquals(CaptureDate("2026-01-02T03:04:05Z"), nowCutoff(clockAt("2026-01-02T03:04:05Z")))
    }

    @Test
    fun `a fetched createdAt is already the cutoff shape and is reused verbatim`() {
        val createdAt = "2026-07-04T18:00:00Z"
        assertEquals(CaptureDate(createdAt), instantToCutoff(Instant.parse(createdAt)))
    }

    @Test
    fun `a local pick converts to the corresponding UTC cutoff`() {
        // 16:32:11 local in CEST (UTC+2, July) is 14:32:11 UTC.
        val local = LocalDateTime(2026, 7, 6, 16, 32, 11)
        assertEquals(CaptureDate("2026-07-06T14:32:11Z"), localToCutoff(local, TimeZone.of("Europe/Berlin")))
    }

    @Test
    fun `the compare against creationDate is a lexicographic at-or-after`() {
        val cutoff = CaptureDate("2026-07-06T14:32:11Z")
        assertTrue(CaptureDate("2026-07-06T14:32:12Z") >= cutoff, "strictly after is in scope")
        assertTrue(CaptureDate("2026-07-06T14:32:11Z") >= cutoff, "equal instant is in scope")
        assertTrue(CaptureDate("2026-07-06T14:32:10Z") < cutoff, "strictly before is out of scope")
        assertTrue(CaptureDate("") < cutoff, "an undated asset (empty creationDate) is out of scope")
    }

    // ── the event-start floor (capability `photo-selection-policy`) ─────────────────────────────────────

    private val startsAt = eventStart("2026-07-14T18:00:00Z")

    @Test
    fun `a cutoff below the event start is clamped up to it`() {
        assertEquals(CaptureCutoff(startsAt.at), clampToFloor(chosen = captureCutoff("2026-07-14T12:00:00Z"), startsAt = startsAt))
        assertEquals(CaptureCutoff(startsAt.at), clampToFloor(chosen = captureCutoff("2001-01-01T00:00:00Z"), startsAt = startsAt))
    }

    @Test
    fun `a cutoff at or above the event start is honored unchanged`() {
        assertEquals(CaptureCutoff(startsAt.at), clampToFloor(chosen = CaptureCutoff(startsAt.at), startsAt = startsAt))
        assertEquals(
            captureCutoff("2026-07-14T21:00:00Z"),
            clampToFloor(chosen = captureCutoff("2026-07-14T21:00:00Z"), startsAt = startsAt),
            "the member chooses freely ABOVE the floor",
        )
    }

    @Test
    fun `the floor only ever narrows scope`() {
        // The whole safety argument in one assertion: whatever the member (or a hostile deeplink) picks,
        // the persisted cutoff is never EARLIER than the event's start — so no photo taken before the
        // event began can be uploaded to it.
        val picks = listOf(captureCutoff(""), captureCutoff("2001-01-01T00:00:00Z"), CaptureCutoff(startsAt.at), captureCutoff("2099-12-31T23:59:59Z"))
        for (chosen in picks) {
            assertTrue(
                clampToFloor(chosen, startsAt).at >= startsAt.at,
                "clamp($chosen) must not fall below the floor",
            )
        }
    }

    @Test
    fun `the empty-string cutoff cannot survive the clamp`() {
        // "" is the trapdoor to whole-library scope (every string is >= ""). The floor closes it: even if
        // an empty cutoff reached the clamp, it is raised to the event's start.
        assertEquals(CaptureCutoff(startsAt.at), clampToFloor(chosen = captureCutoff(""), startsAt = startsAt))
    }

    @Test
    fun `a future event start clamps every choice into the future`() {
        // This is what makes "nothing syncs before the event starts" a theorem rather than a gate: a
        // photo's creationDate cannot be in the future, so a future cutoff admits nothing.
        val future = eventStart("2099-12-31T23:59:59Z")
        assertEquals(CaptureCutoff(future.at), clampToFloor(chosen = captureCutoff("2026-07-14T12:00:00Z"), startsAt = future))
        assertTrue(CaptureDate("2026-07-14T12:00:00Z") < future.at, "no photo of today satisfies a 2099 cutoff")
    }

    private val endsAt = eventEnd("2026-07-21T18:00:00Z")

    @Test
    fun `an upper bound above the event end is clamped down to it`() {
        assertEquals(CaptureCeiling(endsAt.at), clampToCeiling(chosen = captureCeiling("2026-07-31T00:00:00Z"), endsAt = endsAt))
        assertEquals(CaptureCeiling(endsAt.at), clampToCeiling(chosen = captureCeiling("2099-01-01T00:00:00Z"), endsAt = endsAt))
    }

    @Test
    fun `an upper bound at or below the event end is honored unchanged`() {
        assertEquals(CaptureCeiling(endsAt.at), clampToCeiling(chosen = CaptureCeiling(endsAt.at), endsAt = endsAt))
        assertEquals(
            captureCeiling("2026-07-18T09:00:00Z"),
            clampToCeiling(chosen = captureCeiling("2026-07-18T09:00:00Z"), endsAt = endsAt),
        )
    }

    @Test
    fun `the ceiling only ever narrows scope`() {
        // Mirror of the floor property: the persisted upper bound is never ABOVE the event end, whatever
        // the member picked — so the event can only narrow a membership's window, never widen it.
        for (chosen in listOf(captureCeiling("2026-07-14T00:00:00Z"), CaptureCeiling(endsAt.at), captureCeiling("2030-01-01T00:00:00Z"))) {
            assertTrue(clampToCeiling(chosen, endsAt).at <= endsAt.at, "clampToCeiling($chosen) must be <= endsAt")
        }
    }
}
