package app.snapsync.attest

import app.snapsync.keychain.ACCESSIBLE_AFTER_FIRST_UNLOCK
import app.snapsync.keychain.StubKeychain
import app.snapsync.ports.KeychainRead
import app.snapsync.ports.KeychainUnavailable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The attestation store's two items and the one asymmetry between them (capability
 * `device-attestation`).
 *
 * The store is four one-line delegations, which is exactly why it is worth pinning: the lines are
 * interchangeable-looking and the consequences are not. `clearToken` must drop the **token** and keep
 * the **keyId** — the Secure-Enclave key is still good, so the app recovers with a cheap assertion
 * instead of a throttled re-attestation, and Apple rate-limits attestation hard enough that getting
 * this backwards degrades quietly into a device that cannot re-authenticate for a long while.
 *
 * The reads must also draw the absence line the whole capability rests on: a locked device raises,
 * it never answers "no token". Answering absence there would tell `DeviceAttestation` this device has
 * never attested, which is the same input a fresh install gives — so a background wake on a locked
 * phone would look like a first run.
 */
class KeychainAttestStoreTest {

    private val token = StubKeychain()
    private val keyId = StubKeychain()
    private val store = KeychainAttestStore(tokenItem = token, keyIdItem = keyId)

    @Test
    fun `a stored token and keyId are read back verbatim`() {
        store.setToken("bearer-abc")
        store.setKeyId("enclave-key-1")

        assertEquals("bearer-abc", store.token())
        assertEquals("enclave-key-1", store.keyId())
    }

    @Test
    fun `an item that was never written reads as no value`() {
        assertNull(store.token(), "not attested yet is a legitimate answer — there is nothing to mint")
        assertNull(store.keyId())
    }

    @Test
    fun `an unreadable token raises rather than reporting that this device never attested`() {
        val locked = KeychainAttestStore(
            tokenItem = StubKeychain(KeychainRead.Unavailable(-25308)),
            keyIdItem = keyId,
        )

        assertFailsWith<KeychainUnavailable> { locked.token() }
    }

    @Test
    fun `an unreadable keyId raises for the same reason as the token`() {
        val locked = KeychainAttestStore(
            tokenItem = token,
            keyIdItem = StubKeychain(KeychainRead.Unavailable(-25308)),
        )

        assertFailsWith<KeychainUnavailable> { locked.keyId() }
    }

    /** The asymmetry. Clearing both would force a throttled re-attestation on every rejection. */
    @Test
    fun `clearing the token keeps the keyId`() {
        store.setToken("bearer-abc")
        store.setKeyId("enclave-key-1")

        store.clearToken()

        assertNull(store.token(), "the rejected credential must be gone")
        assertEquals("enclave-key-1", store.keyId(), "the Secure-Enclave key survives a rejected token")
        assertEquals(0, keyId.deletes, "the keyId item must not even be touched by clearToken")
    }

    /**
     * Both items are read through `readExisting`, so a legacy-accessibility item is upgraded in place
     * on the next read. Without it, a token written by a pre-fix build stays unreadable to the upload
     * extension for ever, and every background upload goes out unauthenticated.
     */
    @Test
    fun `a legacy-accessibility token is upgraded in place on read`() {
        val old = StubKeychain(KeychainRead.Found("bearer-from-june", "ak")) // ak = WhenUnlocked
        val legacyStore = KeychainAttestStore(tokenItem = old, keyIdItem = keyId)

        assertEquals("bearer-from-june", legacyStore.token())
        assertEquals(1, old.migrations)
        assertTrue(old.writes.isEmpty(), "the value is preserved; only the accessibility class changes")
    }

    /** Every write goes through the one adapter, so every item is background-readable by construction. */
    @Test
    fun `a written token is stored background-readable`() {
        store.setToken("bearer-abc")

        assertEquals(
            KeychainRead.Found("bearer-abc", ACCESSIBLE_AFTER_FIRST_UNLOCK),
            token.read(),
            "the OS invokes the upload extension when the device is idle — which usually means locked",
        )
    }
}
