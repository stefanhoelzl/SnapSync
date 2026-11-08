package app.snapsync.format

import app.snapsync.domain.event.EventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The silent-push payload codec (capability `receiving-photos`; migration step 12). The Swift shell
 * forwards `userInfo` whole; this is the one tested place that knows the field — so a malformed or
 * foreign push resolves to `null` (no fan-out) rather than a crash or a phantom event id.
 */
class PushPayloadTextTest {
    private val e1 = EventId.parse("0e1a5c3f-1b4c-4d2e-9e7a-1b2c3d4e5f60")!!

    @Test
    fun `extracts the eventId string`() {
        assertEquals(
            e1,
            PushPayloadText.eventId(mapOf<Any?, Any?>("eventId" to e1.value, "aps" to mapOf<Any?, Any?>())),
        )
    }

    @Test
    fun `an FCM data message’s string map yields its eventId`() {
        // FCM hands a data message over as `Map<String, String>`: the same key the APNs payload carries.
        assertEquals(e1, PushPayloadText.eventId(mapOf("eventId" to e1.value)))
    }

    @Test
    fun `an eventId that names no event is no eventId`() {
        // The backend issues every event id as a canonical UUID: any other string reaches no arm, like an absent one.
        assertEquals(null, PushPayloadText.eventId(mapOf("eventId" to "E1")))
    }

    @Test
    fun `a payload without an eventId is null`() {
        assertNull(PushPayloadText.eventId(mapOf<Any?, Any?>("aps" to mapOf<Any?, Any?>("content-available" to 1))))
    }

    @Test
    fun `a non-string eventId is null`() {
        assertNull(PushPayloadText.eventId(mapOf<Any?, Any?>("eventId" to 42)))
    }

    @Test
    fun `an empty payload is null`() {
        assertNull(PushPayloadText.eventId(emptyMap<Any?, Any?>()))
    }
}
