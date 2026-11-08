package app.snapsync.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/** What the push port hands the core, held as the platform delivered it. */
class PushValuesTest {

    @Test
    fun `a push token compares by its value`() {
        assertEquals(PushToken("abc"), PushToken("abc"))
        assertNotEquals(PushToken("abc"), PushToken("def"))
    }

    /**
     * Holding the value IS the whole contract: the platform's payload (an APNs/FCM dictionary) handed through
     * untouched, read by the core and never compared.
     */
    @Test
    fun `a push message hands its payload through untouched`() {
        val payload = mapOf<Any?, Any?>("seq" to 3)
        assertSame(payload, PushMessage(payload).payload)
    }
}
