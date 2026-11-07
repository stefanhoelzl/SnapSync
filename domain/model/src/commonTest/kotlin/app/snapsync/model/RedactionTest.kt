package app.snapsync.model

import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.MakesEventKey
import app.snapsync.format.EventKeyText
import app.snapsync.format.LINK_ORIGIN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedactionTest {

    @Test
    fun `no event key survives a link line`() {
        val eventId = "550e8400-e29b-41d4-a716-446655440000"

        @OptIn(MakesEventKey::class)
        val text = EventKeyText.encode(EventKey.of(ByteArray(EventKey.LENGTH) { it.toByte() })!!)
        val line = "onOpenUrl url=$LINK_ORIGIN/join/$eventId#k=$text&autoJoin=true"
        val scrubbed = redactEventKeys(line)
        assertTrue(text !in scrubbed && REDACTED_KEY in scrubbed, scrubbed)
        assertTrue(eventId in scrubbed, "the event's id stays")
        assertEquals("no key here", redactEventKeys("no key here"))
    }
}
