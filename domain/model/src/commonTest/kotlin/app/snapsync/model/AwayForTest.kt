package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A return's time away (capability `create-event`): measured from the leave, and none on a cold launch. */
class AwayForTest {

    @Test
    fun `a return is away from the leave until now`() {
        val left = Instant.parse("2026-10-08T10:00:00Z")
        assertEquals(5.minutes, awayFor(left, left + 5.minutes))
    }

    @Test
    fun `a cold launch was never away`() {
        assertNull(awayFor(null, Instant.parse("2026-10-08T10:00:00Z")))
    }
}
