package app.snapsync.data.trust

import app.snapsync.data.RecordingSecureStore
import app.snapsync.data.backend.TokenRead
import app.snapsync.domain.trust.AttestKeyId
import app.snapsync.domain.trust.BearerToken
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection
import app.snapsync.ports.WriteOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LOCKED = "OSStatus -25308"

private val TOKEN = TrustSlots.ATTEST_TOKEN
private val KEY_ID = TrustSlots.ATTEST_KEY_ID

private fun bearer(text: String) = TokenText.parse(text)

private fun TokenRead.text(): String? = held()?.secret

private fun KeyIdRead.text(): String? = (this as? KeyIdRead.Held)?.keyId?.value

/**
 * The credential's two slots and the one asymmetry between them (capability `privacy-security`), and the in-memory copy
 * each process keeps in front of them.
 *
 * Clearing a rejected token must drop the **token** and keep the **key id** — the key is still good, so the app recovers
 * with a cheap assertion instead of a throttled re-attestation. The reads draw the absence line the capability rests on:
 * a locked device answers `Unavailable`, never "no token" — which is the input a fresh install gives, so a background
 * wake on a locked phone would look like a first run.
 */
class CredentialStoreTest {

    private val secure = RecordingSecureStore()
    private val record = SecureCredentialStore(secure)

    // ---- the store of record ----

    @Test
    fun `a stored token and key id are read back verbatim from their own slots`() {
        record.setToken(bearer("bearer-abc"))
        record.setKeyId(AttestKeyId("enclave-key-1"))

        assertEquals("bearer-abc", record.token().text())
        assertEquals("enclave-key-1", record.keyId().text())
        assertEquals(listOf("bearer-abc"), secure.writesTo(TOKEN))
        assertEquals(listOf("enclave-key-1"), secure.writesTo(KEY_ID))
    }

    @Test
    fun `a slot that was never written reads as none`() {
        assertEquals(
            TokenRead.None,
            record.token(),
            "not attested yet is a legitimate answer — there is nothing to mint",
        )
        assertEquals(KeyIdRead.None, record.keyId())
        assertTrue(secure.untouched(), "a read never writes")
    }

    @Test
    fun `an unreadable slot is unavailable - never no token`() {
        secure.answers[TOKEN] = SecureStoreRead.Unavailable(LOCKED)
        secure.answers[KEY_ID] = SecureStoreRead.Unavailable(LOCKED)
        assertEquals(TokenRead.Unavailable(LOCKED), record.token())
        assertEquals(KeyIdRead.Unavailable(LOCKED), record.keyId())
        assertEquals(ClearRead.Unavailable(LOCKED), record.clearTokenIf(bearer("T1")), "and nothing is cleared over it")
        assertTrue(secure.deletes.isEmpty())
    }

    @Test
    fun `a refused write is answered and stores nothing`() {
        secure.refuseWrites = true
        assertIs<WriteOutcome.Failed>(record.setToken(bearer("bearer-abc")))
        assertIs<WriteOutcome.Failed>(record.setKeyId(AttestKeyId("enclave-key-1")))
        assertEquals(TokenRead.None, record.token(), "nothing was stored")
        assertEquals(KeyIdRead.None, record.keyId())
    }

    /** The asymmetry. Clearing both would force a throttled re-attestation on every rejection. */
    @Test
    fun `clearing the token keeps the key id`() {
        record.setToken(bearer("bearer-abc"))
        record.setKeyId(AttestKeyId("enclave-key-1"))

        assertEquals(ClearRead.Cleared, record.clearTokenIf(bearer("bearer-abc")))

        assertEquals(TokenRead.None, record.token(), "the rejected credential must be gone")
        assertEquals("enclave-key-1", record.keyId().text(), "the Secure-Enclave key survives a rejected token")
        assertEquals(listOf(TOKEN), secure.deletes, "the key id slot is not even touched")
    }

    /** Compare-and-clear: a late rejection of T1 must not erase the T2 a renewal already stored. */
    @Test
    fun `clearing clears only the token it names`() {
        record.setToken(bearer("bearer-T2"))

        assertEquals(ClearRead.NotHeld, record.clearTokenIf(bearer("bearer-T1")), "a stale rejection clears nothing")
        assertEquals("bearer-T2", record.token().text())
        assertTrue(secure.deletes.isEmpty())
        assertEquals(ClearRead.NotHeld, SecureCredentialStore(RecordingSecureStore()).clearTokenIf(bearer("x")))
    }

    @Test
    fun `a token under another protection is read verbatim and never rewritten`() {
        secure.answers[TOKEN] = SecureStoreRead.Found("bearer-from-june", StoredProtection.RESTRICTED)
        assertEquals("bearer-from-june", record.token().text())
        assertTrue(secure.untouched(), "a read never writes")
    }

    @Test
    fun `a token reads its own expiry - and one that is not ours has none`() {
        record.setToken(bearer("D.1800000000.sig"))
        assertEquals(1_800_000_000L, record.token().held()?.expiry?.at?.epochSeconds)
        record.setToken(bearer("garbage"))
        assertNull(record.token().held()?.expiry)
    }

    // ---- the in-memory copy ----

    @Test
    fun `repeated reads cost one read of the store - absence included`() {
        val held = CachedCredentialStore(
            SecureCredentialStore(
                RecordingSecureStore().also { s ->
                    s.answers[TOKEN] = SecureStoreRead.Found("T1", StoredProtection.BACKGROUND_READABLE)
                },
            ),
        )
        val none = RecordingSecureStore()
        val absent = CachedCredentialStore(SecureCredentialStore(none))

        repeat(50) { assertEquals("T1", held.token().text()) }
        repeat(5) { assertEquals(TokenRead.None, absent.token()) }
        assertEquals(1, none.readsOf(TOKEN))
    }

    @Test
    fun `an unreadable store is not remembered - the next read tries again`() {
        secure.answers[TOKEN] = SecureStoreRead.Unavailable(LOCKED)
        val cached = CachedCredentialStore(record)
        assertIs<TokenRead.Unavailable>(cached.token())

        secure.answers[TOKEN] = SecureStoreRead.Found("T1", StoredProtection.BACKGROUND_READABLE)
        assertEquals("T1", cached.token().text(), "a locked read must not be cached as anything")
    }

    @Test
    fun `this process never reads its own write stale`() {
        record.setToken(bearer("T1"))
        val cached = CachedCredentialStore(record)
        assertEquals("T1", cached.token().text())

        cached.setToken(bearer("T2"))
        assertEquals("T2", cached.token().text())

        cached.clearTokenIf(bearer("T2"))
        assertEquals(TokenRead.None, cached.token())
    }

    @Test
    fun `the other process's write is seen once re-read - and not before`() {
        record.setToken(bearer("T1"))
        val cached = CachedCredentialStore(record)
        assertEquals("T1", cached.token().text())

        record.setToken(bearer("T2")) // the app renewed, in the other process

        assertEquals("T1", cached.token().text(), "the copy is served between re-reads — that is the saving")
        cached.reread()
        assertEquals("T2", cached.token().text())
    }

    @Test
    fun `a rejection compares against the store of record - not the copy`() {
        record.setToken(bearer("T1"))
        val cached = CachedCredentialStore(record)
        assertEquals("T1", cached.token().text())
        record.setToken(bearer("T2")) // renewed elsewhere while a request carrying T1 was in flight

        assertEquals(ClearRead.NotHeld, cached.clearTokenIf(bearer("T1")), "a late rejection of T1 must not erase T2")
        assertEquals("T2", record.token().text())
        assertEquals("T2", cached.token().text(), "and the rejection re-reads, so the next request carries T2")
    }

    @Test
    fun `a burst of rejections of one token clears it once`() {
        record.setToken(bearer("T1"))
        val cached = CachedCredentialStore(record)
        assertEquals(
            listOf<ClearRead>(ClearRead.Cleared, ClearRead.NotHeld, ClearRead.NotHeld),
            List(3) { cached.clearTokenIf(bearer("T1")) },
        )
    }

    @Test
    fun `the key id passes straight through`() {
        record.setKeyId(AttestKeyId("k1"))
        val cached = CachedCredentialStore(record)
        assertEquals("k1", cached.keyId().text())
        cached.setKeyId(AttestKeyId("k2"))
        assertEquals("k2", record.keyId().text())
    }

    @Test
    fun `a token renders masked - it is a credential`() {
        val token: BearerToken = bearer("D.1800000000.secret-signature")
        assertTrue("secret" !in token.toString())
    }
}
