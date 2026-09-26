package app.snapsync.services.trust

import app.snapsync.ports.AttestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The in-memory token copy (capability `privacy-security`): what it saves, and — the part that matters —
 * that it never serves a token this process has replaced, and that a write by the OTHER process is seen at
 * every re-read. The contract clauses themselves (read-back, clear keeps the keyId) are run against the
 * cached composition in `AttestContractBindingsTest`.
 */
class CachedAttestStoreTest {

    /** The store of record, as both processes see it, counting token reads (each one a Keychain round-trip). */
    private class SharedItem(var held: String? = null, var heldKeyId: String? = null) : AttestStore {
        var tokenReads = 0
        var failNextRead = false

        override fun token(): String? {
            tokenReads++
            if (failNextRead) {
                failNextRead = false
                throw IllegalStateException("locked")
            }
            return held
        }

        override fun setToken(token: String) {
            held = token
        }

        override fun keyId(): String? = heldKeyId

        override fun setKeyId(keyId: String) {
            heldKeyId = keyId
        }

        override fun clearToken() {
            held = null
        }
    }

    @Test
    fun `repeated reads cost one read of the store`() {
        val item = SharedItem(held = "T1")
        val cached = CachedAttestStore(item)

        repeat(50) { assertEquals("T1", cached.token()) }

        assertEquals(1, item.tokenReads)
    }

    @Test
    fun `absence is held like any answer`() {
        val item = SharedItem()
        val cached = CachedAttestStore(item)

        repeat(5) { assertNull(cached.token()) }

        assertEquals(1, item.tokenReads)
    }

    @Test
    fun `an unreadable store is not remembered - the next read tries again`() {
        val item = SharedItem(held = "T1").apply { failNextRead = true }
        val cached = CachedAttestStore(item)

        assertFailsWith<IllegalStateException> { cached.token() }
        assertEquals("T1", cached.token(), "a locked read must not be cached as anything")
    }

    @Test
    fun `this process never reads its own write stale`() {
        val cached = CachedAttestStore(SharedItem(held = "T1"))
        assertEquals("T1", cached.token())

        cached.setToken("T2")
        assertEquals("T2", cached.token())

        cached.clearToken()
        assertNull(cached.token())
    }

    @Test
    fun `the other process's write is seen once re-read - and not before`() {
        val item = SharedItem(held = "T1")
        val cached = CachedAttestStore(item)
        assertEquals("T1", cached.token())

        item.held = "T2" // the app renewed, in the other process

        assertEquals("T1", cached.token(), "the copy is served between re-reads — that is the saving")
        cached.reread()
        assertEquals("T2", cached.token())
    }

    @Test
    fun `a rejection compares against the store of record - not the copy`() {
        val item = SharedItem(held = "T1")
        val cached = CachedAttestStore(item)
        assertEquals("T1", cached.token())
        item.held = "T2" // renewed elsewhere while a request carrying T1 was in flight

        assertFalse(cached.clearTokenIf("T1"), "a late rejection of T1 must not erase T2")

        assertEquals("T2", item.held)
        assertEquals("T2", cached.token(), "and the rejection re-reads, so the next request carries T2")
    }

    @Test
    fun `a rejection of the held token clears it`() {
        val item = SharedItem(held = "T1")
        val cached = CachedAttestStore(item)
        assertEquals("T1", cached.token())

        assertTrue(cached.clearTokenIf("T1"))

        assertNull(item.held)
        assertNull(cached.token())
    }

    @Test
    fun `the keyId passes straight through`() {
        val item = SharedItem(heldKeyId = "k1")
        val cached = CachedAttestStore(item)

        assertEquals("k1", cached.keyId())
        cached.setKeyId("k2")
        assertEquals("k2", item.heldKeyId)
    }
}
