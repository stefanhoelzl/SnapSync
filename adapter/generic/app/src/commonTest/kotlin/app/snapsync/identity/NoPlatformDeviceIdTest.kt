package app.snapsync.identity

import kotlin.test.Test
import kotlin.test.assertNull

/** iOS and the JVM offer no stable id this app may use: the identity service then mints a random one. */
class NoPlatformDeviceIdTest {

    @Test
    fun `it offers no stable id`() {
        assertNull(NoPlatformDeviceId().stableId())
    }
}
