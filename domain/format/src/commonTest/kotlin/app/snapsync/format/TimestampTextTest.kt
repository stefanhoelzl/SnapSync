package app.snapsync.format

import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.time.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The one text form of a timestamp (capability `photo-sharing`): the wire, the stores and the persisted config carry it,
 * so a changed shape strands every joined device.
 */
class TimestampTextTest {

    @Test
    fun `a timestamp's text is the exact UTC Z shape — to the second`() {
        assertEquals(
            "2026-07-06T14:32:11Z",
            TimestampText.format(Timestamp.of(Instant.parse("2026-07-06T14:32:11.987654Z"))),
        )
        assertEquals("2026-01-02T03:04:05Z", TimestampText.format(Timestamp.of(Instant.parse("2026-01-02T03:04:05Z"))))
        assertEquals("0001-01-01T00:00:00Z", TimestampText.format(Timestamp.of(Instant.parse("0001-01-01T00:00:00Z"))))
        assertEquals("9999-12-31T23:59:59Z", TimestampText.format(Timestamp.of(Instant.parse("9999-12-31T23:59:59Z"))))
    }

    @Test
    fun `a text from outside names a timestamp or none`() {
        assertEquals(Instant.parse("2026-07-06T14:32:11Z"), TimestampText.parse("2026-07-06T14:32:11.5Z").at)
        assertEquals(
            TimestampText.parse("2026-07-06T14:32:11Z"),
            TimestampText.parseOrNull("2026-07-06T16:32:11+02:00"),
        )
        assertNull(TimestampText.parseOrNull(""))
        assertNull(TimestampText.parseOrNull("not a date"))
        assertFailsWith<IllegalArgumentException> { TimestampText.parse("not a date") }
    }

    @Test
    fun `a library's capture text is a dated capture — or undated when it names no instant`() {
        assertEquals(
            CaptureDate.Dated(TimestampText.parse("2026-07-06T14:32:11Z")),
            TimestampText.captureDate("2026-07-06T14:32:11Z"),
        )
        assertEquals(CaptureDate.Undated, TimestampText.captureDate(""))
        assertEquals(CaptureDate.Undated, TimestampText.captureDate("not a date"))
        assertEquals(INSTANT, TimestampText.captureText(TimestampText.captureDate(INSTANT)))
        assertEquals("", TimestampText.captureText(CaptureDate.Undated))
    }

    private companion object {
        const val INSTANT = "2026-07-06T14:32:11Z"
    }
}
