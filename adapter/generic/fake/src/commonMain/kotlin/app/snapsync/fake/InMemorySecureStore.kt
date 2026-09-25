package app.snapsync.fake

import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.SecureStoreUnavailable
import app.snapsync.ports.StoredProtection

/**
 * The honest [SecureStore]: one addressed value in memory, held to the same contract as the Keychain and
 * the App-Group file store (`docs/architecture.md`, `SecureStoreContract`).
 *
 * [unavailable] is the "I could not look" store — what a device not unlocked since boot presents. It is
 * fixed at construction: a real store cannot be switched between the two on command either, and a
 * scenario that needs the transition is a test of the caller's logic, not of this port.
 */
internal class InMemorySecureStore(
    private var item: SecureStoreRead.Found?,
    private val unavailable: Boolean,
) : SecureStore {

    override fun read(): SecureStoreRead = when {
        unavailable -> SecureStoreRead.Unavailable(UNAVAILABLE)
        else -> item ?: SecureStoreRead.Absent
    }

    override fun write(value: String) {
        if (unavailable) throw SecureStoreUnavailable(UNAVAILABLE)
        item = SecureStoreRead.Found(value, StoredProtection.BACKGROUND_READABLE)
    }

    /** Best-effort, like every store: an unavailable one keeps what it has. */
    override fun migrateProtection() {
        if (unavailable) return
        item = item?.copy(protection = StoredProtection.BACKGROUND_READABLE)
    }

    /** Deleting an absent item is a no-op; an unavailable store has nothing it can delete. */
    override fun delete() {
        if (unavailable) return
        item = null
    }

    private companion object {
        const val UNAVAILABLE = "in-memory store constructed unavailable"
    }
}
