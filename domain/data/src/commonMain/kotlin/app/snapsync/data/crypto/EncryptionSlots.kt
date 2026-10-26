package app.snapsync.data.crypto

import app.snapsync.ports.SecureSlot

/**
 * The item encryption keeps in the `SecureStore` — runtime identity: the installed base holds the joined event's key
 * under exactly this address, and a re-valued one silently addresses a different real item (`docs/architecture.md`).
 */
object EncryptionSlots {
    /**
     * The joined event's key, when it is encrypted: its text ([app.snapsync.format.EventKeyText]), in the shared group,
     * because the upload extension derives each file's key from it. One slot — one membership.
     */
    val EVENT_KEY = SecureSlot(service = "app.snapsync.eventkey", account = "key", shared = true)
}
