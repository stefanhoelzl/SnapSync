package app.snapsync.feature.creation.readmodel

import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.MakesEventKey
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A minted event carries its id to the join gate, with the invite key only an encrypted event has. */
@OptIn(MakesEventKey::class)
class MintedEventTest {

    @Test
    fun `an encrypted event carries its invite key and a plain one none`() {
        val key = EventKey.of(ByteArray(EventKey.LENGTH))!!
        val encrypted = MintedEvent(testEventId("E"), key)
        assertEquals(testEventId("E"), encrypted.eventId)
        assertEquals(key, encrypted.key)
        assertNull(MintedEvent(testEventId("P"), null).key)
    }
}
