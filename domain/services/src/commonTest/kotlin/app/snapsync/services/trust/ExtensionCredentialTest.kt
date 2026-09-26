package app.snapsync.services.trust

import app.snapsync.ports.AttestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * The upload extension's credential (capability `privacy-security`): it sends whatever the app stored, and on a
 * rejection drops that token — only if it is still the one held — and offers nothing to retry with.
 */
class ExtensionCredentialTest {

    private class Item(var held: String?, var unreadable: Boolean = false) : AttestStore {
        override fun token(): String? = if (unreadable) throw IllegalStateException("locked") else held
        override fun setToken(token: String) { held = token }
        override fun keyId(): String? = "K"
        override fun setKeyId(keyId: String) = Unit
        override fun clearToken() { held = null }
    }

    @Test
    fun a_rejection_drops_the_held_token_and_offers_no_retry() = runTest {
        val item = Item("T1")
        val credential = ExtensionCredential(CachedAttestStore(item))

        assertEquals("T1", credential.token())
        assertNull(credential.rejected("T1"), "the extension cannot attest, so it never retries")
        assertNull(item.held, "dropped — so the app's next wake reads it as stale and re-mints")
    }

    @Test
    fun a_late_rejection_of_a_token_the_app_already_replaced_leaves_the_replacement_alone() = runTest {
        val item = Item("T2")
        ExtensionCredential(CachedAttestStore(item)).rejected("T1")
        assertEquals("T2", item.held)
    }

    @Test
    fun an_unreadable_store_sends_the_call_unauthenticated_and_never_throws() = runTest {
        val item = Item("T1", unreadable = true)
        val credential = ExtensionCredential(CachedAttestStore(item))
        assertNull(credential.token(), "a locked Keychain reads as no token — the call goes out and 401s, retryably")
        assertNull(credential.rejected("T1"), "a compare it cannot make is logged, never thrown into the cycle")
    }
}
