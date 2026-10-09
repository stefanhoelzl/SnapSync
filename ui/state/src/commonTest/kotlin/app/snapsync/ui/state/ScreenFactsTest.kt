package app.snapsync.ui.state

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The facts the screen reads off the state rather than deciding for itself. */
class ScreenFactsTest {

    @Test
    fun `only the unverifiable phone offers a report`() {
        assertEquals(listOf(ScreenMessage.DEVICE_UNVERIFIABLE), ScreenMessage.entries.filter { it.offersReport })
    }

    @Test
    fun `the joined layer has ended only once its timing says so`() {
        val joined = Layer.Joined(
            JoinedEvent(
                eventId = "e",
                name = "Picnic",
                startsAt = LocalDateTime(2026, 7, 6, 0, 0),
                endsAt = LocalDateTime(2026, 7, 7, 0, 0),
                sharesFrom = LocalDateTime(2026, 7, 6, 0, 0),
                sharesUntil = LocalDateTime(2026, 7, 7, 0, 0),
                shares = true,
                receives = true,
                savesToAlbum = false,
            ),
            "https://snapsync.app/e",
            SyncHealth.InSync,
            today = LocalDate(2026, 7, 6),
        )

        assertFalse(joined.ended)
        assertTrue(joined.copy(timing = EventTiming.Ended).ended)
    }
}
