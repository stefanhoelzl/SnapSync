package app.snapsync.domain.foreground

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The app's latest return to the foreground, as the create screen's draft reads it (capability `create-event`). */
class ForegroundReturnTest {

    @Test
    fun `before the first activation there is no return and no absence`() {
        assertEquals(0, ForegroundReturn.NONE.count)
        assertNull(ForegroundReturn.NONE.awayFor)
    }

    @Test
    fun `a return is away from the leave until now, and counted`() {
        val left = Instant.parse("2026-10-08T10:00:00Z")
        val back = ForegroundReturn(count = 2, awayFor = null).next(left, left + 5.minutes)
        assertEquals(3, back.count)
        assertEquals(5.minutes, back.awayFor)
    }

    @Test
    fun `the app is in front or away, and nothing else`() {
        assertEquals(listOf(InFront.IN_FRONT, InFront.AWAY), InFront.entries)
    }

    @Test
    fun `a cold launch was never away`() {
        val first = ForegroundReturn.NONE.next(leftAt = null, now = Instant.parse("2026-10-08T10:00:00Z"))
        assertEquals(1, first.count)
        assertNull(first.awayFor)
    }
}
