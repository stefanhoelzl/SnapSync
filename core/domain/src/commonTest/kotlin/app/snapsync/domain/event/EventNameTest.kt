package app.snapsync.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EventNameTest {

    @Test
    fun `a name is trimmed`() {
        assertEquals("Lake weekend", assertIs<EventNameVerdict.Ok>(EventName.of("  Lake weekend ")).name.text)
    }

    @Test
    fun `a blank name is none`() {
        assertEquals(EventNameVerdict.Blank, EventName.of(" \t "))
    }

    @Test
    fun `a name is at most the backend's length`() {
        assertIs<EventNameVerdict.Ok>(EventName.of("x".repeat(EventName.MAX_LENGTH)))
        assertEquals(EventNameVerdict.TooLong, EventName.of("x".repeat(EventName.MAX_LENGTH + 1)))
    }
}
