package app.snapsync.services.identity

import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureSlots
import app.snapsync.ports.AttestStore
import app.snapsync.ports.SecureStore
import app.snapsync.services.secure.persist
import app.snapsync.services.secure.readExisting

/**
 * The device token and its App Attest `keyId` (capability `privacy-security`): [AttestStore] over two [SecureStore]
 * slots both processes read — the extension must read the token, and the OS invokes it while the device is locked.
 *
 * Reads use `readExisting`, never a mint: there is nothing to generate — a token comes from the backend, a keyId
 * from the Secure Enclave. An unreadable item throws [app.snapsync.ports.SecureStoreUnavailable] rather than read
 * as absent, which would burn a fresh attestation. A refused write throws it too, where the old throwing store
 * did: the attestation then does not accept the token, and a keyId that could not be stored is refused. After a
 * refused write the old value may be gone (a replace is delete-then-add), so the token path re-attests, as before.
 *
 * [clearToken] drops the token but KEEPS the keyId: the Secure-Enclave key is still good, so the app can recover
 * with a cheap assertion rather than a throttled re-attestation. A delete's own failure is not reported: the next
 * refused request clears it again.
 */
class AttestState(
    private val store: SecureStore,
    private val tokenSlot: SecureSlot = SecureSlots.ATTEST_TOKEN,
    private val keyIdSlot: SecureSlot = SecureSlots.ATTEST_KEY_ID,
) : AttestStore {

    override fun token(): String? = readExisting(store, tokenSlot)

    override fun setToken(token: String) = persist(store, tokenSlot, token)

    override fun keyId(): String? = readExisting(store, keyIdSlot)

    override fun setKeyId(keyId: String) = persist(store, keyIdSlot, keyId)

    override fun clearToken() {
        store.delete(tokenSlot)
    }
}
