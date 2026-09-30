package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The silent-push payload codec (capability `receiving-photos`; migration step 12). The Swift shell
 * forwards `userInfo` whole; this is the one tested place that knows the field — so a malformed or
 * foreign push resolves to `null` (no fan-out) rather than a crash or a phantom event id.
 */
class PushPayloadTest {

    @Test
    fun `extracts the eventId string`() {
        assertEquals("E1", pushEventId(mapOf<Any?, Any?>("eventId" to "E1", "aps" to mapOf<Any?, Any?>())))
    }

    @Test
    fun `an FCM data message’s string map yields its eventId`() {
        // FCM hands a data message over as `Map<String, String>`: the same key the APNs payload carries.
        assertEquals("E1", pushEventId(mapOf("eventId" to "E1")))
    }

    @Test
    fun `an endpoint is its kind as well as its token and environment`() {
        // The same token under another push service is another registration (the registration key leads with it).
        assertNotEquals(PushEndpoint(PUSH_KIND_APNS, "T", "e"), PushEndpoint(PUSH_KIND_FCM, "T", "e"))
        assertEquals(PushEndpoint(PUSH_KIND_FCM, "T", "e"), PushEndpoint("fcm", "T", "e"))
    }

    @Test
    fun `a payload without an eventId is null`() {
        assertNull(pushEventId(mapOf<Any?, Any?>("aps" to mapOf<Any?, Any?>("content-available" to 1))))
    }

    @Test
    fun `a non-string eventId is null`() {
        assertNull(pushEventId(mapOf<Any?, Any?>("eventId" to 42)))
    }

    @Test
    fun `an empty payload is null`() {
        assertNull(pushEventId(emptyMap<Any?, Any?>()))
    }
}
