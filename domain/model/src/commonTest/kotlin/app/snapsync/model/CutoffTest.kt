package app.snapsync.model

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CutoffTest {

    @Test
    fun `a capture date is its instant to the second and its text the exact UTC Z shape`() {
        assertEquals("2026-07-06T14:32:11Z", CaptureDate.of(Instant.parse("2026-07-06T14:32:11.987654Z")).iso)
        assertEquals("2026-01-02T03:04:05Z", CaptureDate.of(Instant.parse("2026-01-02T03:04:05Z")).iso)
        assertEquals(Instant.parse("2026-07-06T14:32:11Z"), CaptureDate.parse("2026-07-06T14:32:11.5Z").at)
    }

    @Test
    fun `a text from outside names a capture date or none`() {
        assertEquals(CaptureDate.parse("2026-07-06T14:32:11Z"), CaptureDate.parseOrNull("2026-07-06T16:32:11+02:00"))
        assertEquals(null, CaptureDate.parseOrNull(""))
        assertEquals(null, CaptureDate.parseOrNull("not a date"))
        assertFailsWith<IllegalArgumentException> { CaptureDate.parse("not a date") }
    }

    @Test
    fun `a local pick converts to the corresponding UTC cutoff`() {
        // 16:32:11 local in CEST (UTC+2, July) is 14:32:11 UTC.
        val local = LocalDateTime(2026, 7, 6, 16, 32, 11)
        assertEquals(CaptureDate.parse("2026-07-06T14:32:11Z"), localToCutoff(local, TimeZone.of("Europe/Berlin")))
    }

    @Test
    fun `capture dates compare as instants`() {
        val cutoff = CaptureDate.parse("2026-07-06T14:32:11Z")
        assertTrue(CaptureDate.parse("2026-07-06T14:32:12Z") >= cutoff, "strictly after is in scope")
        assertTrue(CaptureDate.parse("2026-07-06T14:32:11Z") >= cutoff, "equal instant is in scope")
        assertTrue(CaptureDate.parse("2026-07-06T14:32:10Z") < cutoff, "strictly before is out of scope")
        assertTrue(CaptureDate.parse("2026-07-06T16:32:12+02:00") > cutoff, "an offset is no string to mis-sort")
    }

    // ── the share-range rule (capability `join-event`) ─────────────────────────────────────────

    // An event from Tuesday 18:00 to the next Tuesday 18:00.
    private val startsAt = eventStart("2026-07-14T18:00:00Z")
    private val endsAt = eventEnd("2026-07-21T18:00:00Z")

    private fun fault(from: String, until: String) =
        shareRangeFault(captureCutoff(from), captureCeiling(until), startsAt, endsAt)

    @Test
    fun `the event's own range obeys the rule`() {
        assertNull(fault("2026-07-14T18:00:00Z", "2026-07-21T18:00:00Z"))
    }

    @Test
    fun `a range may reach before the start and after the end`() {
        assertNull(fault("2026-07-13T09:00:00Z", "2026-07-21T18:00:00Z"), "the evening before")
        assertNull(fault("2026-07-14T18:00:00Z", "2026-07-23T18:00:00Z"), "two days after")
        assertNull(fault("2026-07-10T00:00:00Z", "2026-07-25T00:00:00Z"), "both")
    }

    @Test
    fun `a range exactly as long as an event may be obeys it — a second more does not`() {
        val from = CaptureDate.parse("2026-07-01T00:00:00Z")
        val longest = CaptureDate.of(from.at + EVENT_WINDOW_MAX_SECONDS.seconds)
        assertNull(shareRangeFault(CaptureCutoff(from), CaptureCeiling(longest), startsAt, endsAt))
        assertEquals(
            ShareRangeFault.TOO_LONG,
            shareRangeFault(
                CaptureCutoff(from),
                CaptureCeiling(CaptureDate.of(longest.at + 1.seconds)),
                startsAt,
                endsAt,
            ),
        )
    }

    @Test
    fun `a range touching the event at one instant overlaps it`() {
        assertNull(fault("2026-07-10T00:00:00Z", "2026-07-14T18:00:00Z"), "ends at the start")
        assertNull(fault("2026-07-21T18:00:00Z", "2026-07-25T00:00:00Z"), "starts at the end")
    }

    @Test
    fun `a range that misses the event is refused`() {
        assertEquals(ShareRangeFault.OUTSIDE_EVENT, fault("2026-07-10T00:00:00Z", "2026-07-14T17:59:59Z"))
        assertEquals(ShareRangeFault.OUTSIDE_EVENT, fault("2026-07-21T18:00:01Z", "2026-07-25T00:00:00Z"))
    }

    @Test
    fun `an inverted range is refused`() {
        assertEquals(ShareRangeFault.INVERTED, fault("2026-07-16T00:00:00Z", "2026-07-15T00:00:00Z"))
    }

    @Test
    fun `a whole camera roll is never a range`() {
        // The promise the old floor kept, now kept by the length: years of photos cannot be one membership's range.
        assertEquals(ShareRangeFault.TOO_LONG, fault("2001-01-01T00:00:00Z", "2026-07-21T18:00:00Z"))
    }

    @Test
    fun `the empty-string cutoff cannot be constructed`() {
        // "" is the trapdoor to whole-library scope (every string is >= ""). A membership's dates hold an instant by
        // construction, so it never reaches the share-range rule — nor does any other off-shape value, for any of the
        // four roles.
        for (bad in listOf("", "not-a-date")) {
            assertFailsWith<IllegalArgumentException> { captureCutoff(bad) }
            assertFailsWith<IllegalArgumentException> { captureCeiling(bad) }
            assertFailsWith<IllegalArgumentException> { eventStart(bad) }
            assertFailsWith<IllegalArgumentException> { eventEnd(bad) }
        }
    }
}
