package app.snapsync.domain.event

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** The event window's bound. */
class EventDatesTest {

    @Test
    fun `an event is at most thirty days long`() {
        assertTrue(EventWindow.MAX == 30.days)
    }
}
