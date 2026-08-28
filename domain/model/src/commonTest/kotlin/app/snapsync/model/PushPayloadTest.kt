package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The silent-push payload codec (capability `push-registration`; migration step 12). The Swift shell
 * forwards `userInfo` whole; this is the one tested place that knows the field — so a malformed or
 * foreign push resolves to `null` (no fan-out) rather than a crash or a phantom event id.
 */
class PushPayloadTest {

    @Test
    fun `extracts the eventId string`() {
        assertEquals("E1", pushEventId(mapOf<Any?, Any?>("eventId" to "E1", "aps" to mapOf<Any?, Any?>())))
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
