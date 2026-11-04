package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The secure store's failures say which: a store that could not be read carries why, and is never an absence. */
class SecureStoreFailuresTest {

    @Test
    fun `an unreadable store carries its detail`() {
        val unreadable = SecureStoreUnavailable("locked")
        assertEquals("locked", unreadable.detail)
        assertTrue("locked" in unreadable.message.orEmpty())
    }
}
