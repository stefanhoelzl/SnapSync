package app.snapsync.services.secure

import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.ports.SecureSlot
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.WriteOutcome

/**
 * Read an existing value without ever minting: `null` when the item is genuinely [SecureStoreRead.Absent], throwing
 * [SecureStoreUnavailable] when it could not be read — so no caller can mistake "the device is locked" for "there is
 * nothing there" (`docs/architecture.md`, "Absence is never silent"). The event key's (4.3 moves it to a sealed read,
 * as trust's moved in 4.1).
 */
fun readExisting(store: SecureStore, slot: SecureSlot): String? =
    when (val read = store.read(slot)) {
        is SecureStoreRead.Found -> read.value
        SecureStoreRead.Absent -> null
        is SecureStoreRead.Unavailable -> throw SecureStoreUnavailable(read.detail)
    }

/** Persist [value] at [slot], or throw [SecureStoreUnavailable]: a refused write fails the operation where it is made. */
fun persist(store: SecureStore, slot: SecureSlot, value: String) {
    when (val written = store.write(slot, value)) {
        WriteOutcome.Ok -> Unit
        is WriteOutcome.Failed -> throw SecureStoreUnavailable("write refused: ${written.detail}")
    }
}

/**
 * The items the services keep in the `SecureStore` — runtime identity: the installed base holds them under exactly
 * these addresses, and a re-valued one silently addresses a different real item (`docs/architecture.md`). Trust's own
 * are `TrustSlots` (`:domain:data`).
 */
object SecureSlots {
    /**
     * The joined event's key, when it is ENCRYPTED (the encrypted file format, `docs/architecture.md`): base64url, in
     * the shared group, because the upload extension derives each file's key from it. One slot — one membership.
     */
    val EVENT_KEY = SecureSlot(service = "app.snapsync.eventkey", account = "key", shared = true)
}
