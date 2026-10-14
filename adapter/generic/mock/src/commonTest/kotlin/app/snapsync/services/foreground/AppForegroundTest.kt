package app.snapsync.services.foreground

import app.snapsync.mock.ClockMock
import app.snapsync.model.ForegroundReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The app's comings and goings from the foreground: whether it is in front, and each return with how long it was away. */
class AppForegroundTest {

    @Test
    fun `each return is counted with how long the app was away - none on a cold launch`() {
        val clock = ClockMock(Instant.parse("2026-07-21T09:00:00Z"))
        val foreground = AppForeground(clock.port(), onEntered = {})
        assertFalse(foreground.isForeground)
        assertEquals(ForegroundReturn.NONE, foreground.returns.value)

        foreground.entered()
        assertTrue(foreground.isForeground)
        assertEquals(1, foreground.returns.value.count)
        assertNull(foreground.returns.value.awayFor, "a cold launch was never away")

        foreground.left()
        assertFalse(foreground.isForeground)
        clock.now = clock.now + 5.minutes
        foreground.entered()
        assertEquals(2, foreground.returns.value.count)
        assertEquals(5.minutes, foreground.returns.value.awayFor)
    }
}
