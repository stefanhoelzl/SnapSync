package app.snapsync.services.identity

import app.snapsync.model.SecureSlots
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.StoredProtection
import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.services.secure.RecordingSecureStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LOCKED = "OSStatus -25308"

private val TOKEN = SecureSlots.ATTEST_TOKEN
private val KEY_ID = SecureSlots.ATTEST_KEY_ID

/**
 * The attestation state's two slots and the one asymmetry between them (capability `privacy-security`).
 *
 * The service is a handful of one-line delegations, which is exactly why it is worth pinning: the lines are
 * interchangeable-looking and the consequences are not. `clearToken` must drop the **token** and keep the
 * **keyId** — the Secure-Enclave key is still good, so the app recovers with a cheap assertion instead of a
 * throttled re-attestation, and Apple rate-limits attestation hard enough that getting this backwards degrades
 * quietly into a device that cannot re-authenticate for a long while.
 *
 * The reads must also draw the absence line the whole capability rests on: a locked device raises, it never
 * answers "no token". Answering absence there would tell `DeviceAttestation` this device has never attested,
 * which is the same input a fresh install gives — so a background wake on a locked phone would look like a first
 * run. And a refused write raises too, where the old throwing store did, so a token that was not stored is not
 * accepted.
 */
class AttestStateTest {

    private val secure = RecordingSecureStore()
    private val state = AttestState(secure)

    @Test
    fun `a stored token and keyId are read back verbatim from their own slots`() {
        state.setToken("bearer-abc")
        state.setKeyId("enclave-key-1")

        assertEquals("bearer-abc", state.token())
        assertEquals("enclave-key-1", state.keyId())
        assertEquals(listOf("bearer-abc"), secure.writesTo(TOKEN))
        assertEquals(listOf("enclave-key-1"), secure.writesTo(KEY_ID))
    }

    @Test
    fun `a slot that was never written reads as no value`() {
        assertNull(state.token(), "not attested yet is a legitimate answer — there is nothing to mint")
        assertNull(state.keyId())
        assertTrue(secure.writes.isEmpty(), "a read never writes")
    }

    @Test
    fun `an unreadable token raises rather than reporting that this device never attested`() {
        secure.answers[TOKEN] = SecureStoreRead.Unavailable(LOCKED)

        val failure = assertFailsWith<SecureStoreUnavailable> { state.token() }
        assertEquals(LOCKED, failure.detail)
    }

    @Test
    fun `an unreadable keyId raises for the same reason as the token`() {
        secure.answers[KEY_ID] = SecureStoreRead.Unavailable(LOCKED)

        assertFailsWith<SecureStoreUnavailable> { state.keyId() }
    }

    @Test
    fun `a refused token write raises so the token is not accepted`() {
        secure.refuseWrites = true

        assertFailsWith<SecureStoreUnavailable> { state.setToken("bearer-abc") }
        assertNull(state.token(), "nothing was stored")
    }

    @Test
    fun `a refused keyId write raises so the key is refused`() {
        secure.refuseWrites = true

        assertFailsWith<SecureStoreUnavailable> { state.setKeyId("enclave-key-1") }
        assertNull(state.keyId())
    }

    /** The asymmetry. Clearing both would force a throttled re-attestation on every rejection. */
    @Test
    fun `clearing the token keeps the keyId`() {
        state.setToken("bearer-abc")
        state.setKeyId("enclave-key-1")

        state.clearToken()

        assertNull(state.token(), "the rejected credential must be gone")
        assertEquals("enclave-key-1", state.keyId(), "the Secure-Enclave key survives a rejected token")
        assertEquals(listOf(TOKEN), secure.deletes, "the keyId slot must not even be touched by clearToken")
    }

    /** Compare-and-clear: a late rejection of T1 must not erase the T2 a renewal already stored. */
    @Test
    fun `clearTokenIf clears only the token it names`() {
        state.setToken("bearer-T2")

        assertFalse(state.clearTokenIf("bearer-T1"), "a stale rejection clears nothing")
        assertEquals("bearer-T2", state.token())
        assertTrue(secure.deletes.isEmpty())

        assertTrue(state.clearTokenIf("bearer-T2"))
        assertNull(state.token())
    }

    /**
     * Both slots are read through `readExisting`, so a legacy-protection item is upgraded in place on the next
     * read. Without it, a token written by a pre-fix build stays unreadable to the upload extension for ever, and
     * every background upload goes out unauthenticated.
     */
    @Test
    fun `a legacy-protection token is upgraded in place on read`() {
        secure.answers[TOKEN] = SecureStoreRead.Found("bearer-from-june", StoredProtection.RESTRICTED)

        assertEquals("bearer-from-june", state.token())
        assertEquals(listOf(TOKEN), secure.migrations)
        assertTrue(secure.writes.isEmpty(), "the value is preserved; only the protection changes")
    }

    /** Every write goes through the one store, so every item is background-readable by construction. */
    @Test
    fun `a written token is stored background-readable`() {
        state.setToken("bearer-abc")

        assertEquals(
            SecureStoreRead.Found("bearer-abc", StoredProtection.BACKGROUND_READABLE),
            secure.read(TOKEN),
            "the OS invokes the upload extension when the device is idle — which usually means locked",
        )
    }
}
