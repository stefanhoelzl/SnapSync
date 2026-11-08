package app.snapsync.data.push

import app.snapsync.ports.PushMessage
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a delivered silent push names: its event and the union position it announces, read by the payload's codec. */
class PushPayloadTest {
    @Test
    fun `a delivered push names its event and its position`() {
        val message = PushMessage(mapOf("eventId" to testEventId("E1").value, "seq" to 7L))

        assertEquals(testEventId("E1"), PushPayload.eventId(message))
        assertEquals(7L, PushPayload.seq(message))
    }

    @Test
    fun `a push that names nothing names nothing`() {
        assertNull(PushPayload.eventId(PushMessage(emptyMap<Any?, Any?>())))
        assertNull(PushPayload.seq(PushMessage(emptyMap<Any?, Any?>())))
    }
}
