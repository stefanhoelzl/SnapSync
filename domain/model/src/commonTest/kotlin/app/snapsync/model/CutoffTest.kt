package app.snapsync.model

import app.snapsync.domain.event.DeletesAt
import app.snapsync.format.TimestampText
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CutoffTest {

    @Test
    fun `a local pick converts to the corresponding UTC cutoff`() {
        // 16:32:11 local in CEST (UTC+2, July) is 14:32:11 UTC.
        val local = LocalDateTime(2026, 7, 6, 16, 32, 11)
        assertEquals(TimestampText.parse("2026-07-06T14:32:11Z"), localToCutoff(local, TimeZone.of("Europe/Berlin")))
    }

    @Test
    fun `a membership is confirmed gone only once its deletion date has passed`() {
        val deletesAt = DeletesAt(TimestampText.parse("2026-07-31T09:00:00Z"))
        assertFalse(confirmedGone(deletesAt, TimestampText.parse("2026-07-31T09:00:00Z")))
        assertTrue(confirmedGone(deletesAt, TimestampText.parse("2026-07-31T09:00:01Z")))
    }
}
