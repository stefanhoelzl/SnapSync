package app.snapsync.data.trust

import app.snapsync.ports.SecureSlot

/**
 * The items trust keeps in the `SecureStore` — runtime identity: the installed base holds them under exactly these
 * addresses, and a re-valued one silently addresses a different real item (`docs/architecture.md`).
 */
object TrustSlots {
    /** The device id, in the shared group both processes read. */
    val DEVICE_ID = SecureSlot(service = "app.snapsync.deviceid", account = "deviceid", shared = true)

    /** The attestation token and its App Attest key id. Unscoped (pinned). */
    val ATTEST_TOKEN = SecureSlot(service = "app.snapsync.attest", account = "token", shared = false)
    val ATTEST_KEY_ID = SecureSlot(service = "app.snapsync.attest", account = "keyid", shared = false)
}
