package app.snapsync.model

import app.snapsync.domain.event.DeletesAt
import app.snapsync.format.TimestampText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CutoffTest {

    @Test
    fun `a membership is confirmed gone only once its deletion date has passed`() {
        val deletesAt = DeletesAt(TimestampText.parse("2026-07-31T09:00:00Z"))
        assertFalse(confirmedGone(deletesAt, TimestampText.parse("2026-07-31T09:00:00Z")))
        assertTrue(confirmedGone(deletesAt, TimestampText.parse("2026-07-31T09:00:01Z")))
    }
}
