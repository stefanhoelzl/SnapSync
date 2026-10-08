package app.snapsync.feature.creation.readmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A minted event carries its id to the join gate, with the invite key only an encrypted event has. */
class MintedEventTest {

    @Test
    fun `an encrypted event carries its invite key and a plain one none`() {
        val encrypted = MintedEvent("E", "key")
        assertEquals("E", encrypted.eventId)
        assertEquals("key", encrypted.linkKey)
        assertNull(MintedEvent("P", null).linkKey)
    }
}
