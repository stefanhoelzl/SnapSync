package app.snapsync.domain.sharing

import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.EventWindow
import app.snapsync.domain.time.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The one rule a membership's capture range obeys (capability `join-event`). */
class CaptureRangeTest {

    // An event from Tuesday 18:00 to the next Tuesday 18:00.
    private val start = EventStart(ts("2026-07-14T18:00:00Z"))
    private val end = EventEnd(ts("2026-07-21T18:00:00Z"))

    private fun verdict(from: String, until: String) =
        CaptureRange.of(CaptureCutoff(ts(from)), CaptureCeiling(ts(until)), start, end)

    private fun assertOk(from: String, until: String, message: String? = null) {
        val range = assertIs<CaptureRangeVerdict.Ok>(verdict(from, until), message).range
        assertEquals(ts(from), range.from.at)
        assertEquals(ts(until), range.until.at)
    }

    @Test
    fun `the event's own range is a range`() {
        assertOk("2026-07-14T18:00:00Z", "2026-07-21T18:00:00Z")
    }

    @Test
    fun `a range may reach before the start and after the end`() {
        assertOk("2026-07-13T09:00:00Z", "2026-07-21T18:00:00Z", "the evening before")
        assertOk("2026-07-14T18:00:00Z", "2026-07-23T18:00:00Z", "two days after")
        assertOk("2026-07-10T00:00:00Z", "2026-07-25T00:00:00Z", "both")
    }

    @Test
    fun `a range of one instant is a range`() {
        assertOk("2026-07-15T00:00:00Z", "2026-07-15T00:00:00Z")
    }

    @Test
    fun `a range exactly as long as an event may be is one — a second more is not`() {
        val from = ts("2026-07-01T00:00:00Z")
        val longest = Timestamp.of(from.at + EventWindow.MAX)
        assertIs<CaptureRangeVerdict.Ok>(CaptureRange.of(CaptureCutoff(from), CaptureCeiling(longest), start, end))
        assertEquals(
            CaptureRangeVerdict.TooLong,
            CaptureRange.of(CaptureCutoff(from), CaptureCeiling(Timestamp.of(longest.at + 1.seconds)), start, end),
        )
    }

    @Test
    fun `a range touching the event at one instant overlaps it`() {
        assertOk("2026-07-10T00:00:00Z", "2026-07-14T18:00:00Z", "ends at the start")
        assertOk("2026-07-21T18:00:00Z", "2026-07-25T00:00:00Z", "starts at the end")
    }

    @Test
    fun `a range that misses the event is refused`() {
        assertEquals(CaptureRangeVerdict.OutsideEvent, verdict("2026-07-10T00:00:00Z", "2026-07-14T17:59:59Z"))
        assertEquals(CaptureRangeVerdict.OutsideEvent, verdict("2026-07-21T18:00:01Z", "2026-07-25T00:00:00Z"))
    }

    @Test
    fun `an inverted range is refused`() {
        assertEquals(CaptureRangeVerdict.Inverted, verdict("2026-07-16T00:00:00Z", "2026-07-15T00:00:00Z"))
    }

    @Test
    fun `a whole camera roll is never a range`() {
        // The promise the old floor kept, now kept by the length: years of photos cannot be one membership's range.
        assertEquals(CaptureRangeVerdict.TooLong, verdict("2001-01-01T00:00:00Z", "2026-07-21T18:00:00Z"))
    }

    private fun ts(text: String) = Timestamp.of(Instant.parse(text))
}
