package app.snapsync.gallery

import platform.Foundation.NSISO8601DateFormatWithInternetDateTime
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.timeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shared formatter is a default one ([Iso8601]): its parse is the importer's per-call one. */
class Iso8601Test {

    @Test
    fun `the shared formatter carries Foundation's default options`() {
        assertEquals(NSISO8601DateFormatter().formatOptions, Iso8601.internetDateTime.formatOptions)
        assertEquals(NSISO8601DateFormatWithInternetDateTime, Iso8601.internetDateTime.formatOptions)
    }

    @Test
    fun `parse accepts second precision only as the importer's per-call formatter did`() {
        val plain = "2026-07-09T19:24:17Z"
        assertEquals(
            NSISO8601DateFormatter().dateFromString(plain)?.timeIntervalSince1970,
            Iso8601.parse(plain)?.timeIntervalSince1970,
        )
        assertNull(Iso8601.parse("2026-07-09T19:24:17.182Z"))
        assertNull(Iso8601.parse("not a date"))
    }
}
