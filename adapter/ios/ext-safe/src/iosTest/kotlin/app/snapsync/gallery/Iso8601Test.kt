package app.snapsync.gallery

import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatWithFractionalSeconds
import platform.Foundation.NSISO8601DateFormatWithInternetDateTime
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The shared formatters are **byte-identical** to the per-call ones they replaced ([Iso8601]).
 *
 * A capture date is compared lexicographically in `commonMain` and published in the manifest, so a formatter
 * that drifted by one option (a fraction, a time zone) would change what the policy admits and what the event
 * sees. Pinned against freshly built formatters rather than against literals alone, so the pin holds whatever
 * Foundation's defaults are — the claim is "identical to before", not "equal to this string".
 */
class Iso8601Test {

    private val instants = listOf(0.0, 1_780_000_000.0, 1_780_000_000.182, 1_799_999_999.999)

    @Test
    fun `the shared formatter formats exactly as a fresh default one`() {
        for (seconds in instants) {
            val date = NSDate.dateWithTimeIntervalSince1970(seconds)
            assertEquals(NSISO8601DateFormatter().stringFromDate(date), Iso8601.format(date), "at $seconds")
        }
        assertEquals("1970-01-01T00:00:00Z", Iso8601.format(NSDate.dateWithTimeIntervalSince1970(0.0)))
    }

    @Test
    fun `the shared formatter carries Foundation's default options`() {
        assertEquals(NSISO8601DateFormatter().formatOptions, Iso8601.internetDateTime.formatOptions)
        assertEquals(NSISO8601DateFormatWithInternetDateTime, Iso8601.internetDateTime.formatOptions)
        assertEquals(
            NSISO8601DateFormatWithInternetDateTime or NSISO8601DateFormatWithFractionalSeconds,
            Iso8601.withFractionalSeconds.formatOptions,
        )
    }

    @Test
    fun `parse accepts second precision only as the importer's per-call formatter did`() {
        val plain = "2026-07-09T19:24:17Z"
        assertEquals(NSISO8601DateFormatter().dateFromString(plain)?.timeIntervalSince1970, Iso8601.parse(plain)?.timeIntervalSince1970)
        assertNull(Iso8601.parse("2026-07-09T19:24:17.182Z"))
        assertNull(Iso8601.parse("not a date"))
    }

    @Test
    fun `parseTolerant also accepts a fraction`() {
        assertNotNull(Iso8601.parseTolerant("2026-07-09T19:24:17Z"))
        assertEquals(
            Iso8601.parseTolerant("2026-07-09T19:24:17Z")!!.timeIntervalSince1970 + 0.182,
            Iso8601.parseTolerant("2026-07-09T19:24:17.182Z")!!.timeIntervalSince1970,
            absoluteTolerance = 0.0005,
        )
        assertNull(Iso8601.parseTolerant("not a date"))
    }
}
