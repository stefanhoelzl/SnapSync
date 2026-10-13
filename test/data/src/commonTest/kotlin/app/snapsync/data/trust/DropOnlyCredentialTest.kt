package app.snapsync.data.trust

import app.snapsync.data.RecordingSecureStore
import app.snapsync.data.backend.TokenRead
import app.snapsync.ports.SecureStoreRead
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The upload extension's credential (capability `privacy-security`): it reads the token the app stored, drops one the
 * backend rejects — only if still held — and never recovers, never retries, never attests.
 */
class DropOnlyCredentialTest {
    private val secure = RecordingSecureStore()
    private val record = SecureCredentialStore(secure)
    private val credential = DropOnlyCredential(CachedCredentialStore(record))

    @Test
    fun a_rejection_drops_the_held_token_and_offers_no_retry() = runTest {
        record.setToken(TokenText.parse("T1"))
        assertEquals("T1", credential.token().held()?.secret)
        assertEquals(TokenRead.None, credential.rejected(TokenText.parse("T1")), "the extension cannot attest")
        assertEquals(TokenRead.None, record.token(), "dropped — so the app's next wake obtains a new one")
    }

    @Test
    fun a_late_rejection_of_a_token_the_app_already_replaced_leaves_the_replacement_alone() = runTest {
        record.setToken(TokenText.parse("T2"))
        credential.rejected(TokenText.parse("T1"))
        assertEquals("T2", record.token().held()?.secret)
    }

    @Test
    fun a_missing_token_is_never_obtained_here() = runTest {
        assertEquals(TokenRead.None, credential.missing(), "the extension cannot attest")
        assertEquals(TokenRead.None, record.token())
    }

    @Test
    fun an_unreadable_store_is_read_as_unavailable_and_a_rejection_over_it_never_throws() = runTest {
        secure.answers[TrustSlots.ATTEST_TOKEN] = SecureStoreRead.Unavailable("locked")
        assertIs<TokenRead.Unavailable>(credential.token(), "never mistaken for no token")
        assertEquals(
            TokenRead.None,
            credential.rejected(TokenText.parse("T1")),
            "a compare it cannot make is only logged",
        )
    }
}
