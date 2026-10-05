package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The union position a silent push announces (decision record `changes/incremental-union`, D6), as each platform's
 * dictionary delivers it: APNs' JSON number bridged to whatever number type the platform chose, FCM's string.
 */
class PushSeqTest {

    @Test
    fun a_number_of_any_bridged_type_or_a_string_is_the_position() {
        assertEquals(42L, pushSeq(mapOf("seq" to 42L)))
        assertEquals(42L, pushSeq(mapOf("seq" to 42)))
        assertEquals(42L, pushSeq(mapOf("seq" to 42.0)))
        assertEquals(42L, pushSeq(mapOf("seq" to "42")))
    }

    @Test
    fun no_position_or_one_that_is_none_reads_as_before() {
        assertNull(pushSeq(mapOf("eventId" to "E")), "the close wake, or an older backend")
        assertNull(pushSeq(mapOf("seq" to "x")))
        assertNull(pushSeq(mapOf("seq" to 4.5)))
    }
}
