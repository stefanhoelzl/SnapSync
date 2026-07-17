package app.snapsync.attest

import app.snapsync.ports.AttestStore

import app.snapsync.keychain.ACCESSIBLE_AFTER_FIRST_UNLOCK
import app.snapsync.keychain.IosKeychain
import app.snapsync.ports.Keychain
import app.snapsync.ports.readExisting

/**
 * The iOS [AttestStore]: the device token and its `keyId`, each a Keychain generic-password item in the
 * **shared** access group — the same group holding the device id, so the upload extension reads exactly
 * what the app wrote.
 *
 * Both items are stored `kSecAttrAccessibleAfterFirstUnlock`. This is not a default; it is required. The
 * OS invokes the upload extension when the device is idle — which usually means **locked** — and the
 * extension must read the token to put it on the request. Under the stricter `WhenUnlocked` class it could
 * not, and every background upload would go out unauthenticated.
 *
 * Reads use `readExisting`, never `resolveOrMint`: there is nothing to mint. Unlike the device id, a token
 * is not something this device can generate — it is issued by the backend against an attestation. "Absent"
 * simply means "not attested yet", and the app attests at its next wake.
 *
 * Like the device id, and unlike a `…ThisDeviceOnly` item, the token is **restorable from an encrypted
 * backup**. That is a deliberate trade: a restored device keeps working with no round-trip, at the cost of
 * a backup-extracted token being a usable write credential until it expires. The 30-day lifetime is the
 * only bound on that — which is a reason never to lengthen it.
 */
class KeychainAttestStore(
    private val tokenItem: Keychain = IosKeychain(service = "app.snapsync.attest", account = "token"),
    private val keyIdItem: Keychain = IosKeychain(service = "app.snapsync.attest", account = "keyid"),
) : AttestStore {

    override fun token(): String? = readExisting(tokenItem, ACCESSIBLE_AFTER_FIRST_UNLOCK)

    override fun setToken(token: String) = tokenItem.write(token)

    override fun keyId(): String? = readExisting(keyIdItem, ACCESSIBLE_AFTER_FIRST_UNLOCK)

    override fun setKeyId(keyId: String) = keyIdItem.write(keyId)

    // Drop the token but KEEP the keyId: the Secure-Enclave key is still good, so the app can recover with
    // a cheap assertion rather than a throttled re-attestation.
    override fun clearToken() = tokenItem.delete()
}
