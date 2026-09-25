package app.snapsync.fake

import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.StoredProtection
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.SecureStore

/**
 * The honest [SecureStore]: one value per slot in [items] (the caller's own cell), held to the same contract as
 * the Keychain and the simulator's App-Group file store (`SecureStoreContract`).
 *
 * [unavailable] is the "I could not look" store — what a device not unlocked since boot presents: every read is
 * `Unavailable` and every write `Failed`. It is fixed at construction: a real store cannot be switched between
 * the two on command either, and a scenario that needs the transition is a test of the caller's logic.
 */
internal class InMemorySecureStore(
    private val items: MutableMap<SecureSlot, SecureStoreRead.Found>,
    private val unavailable: Boolean,
) : SecureStore {

    override fun read(slot: SecureSlot): SecureStoreRead = when {
        unavailable -> SecureStoreRead.Unavailable(UNAVAILABLE)
        else -> items[slot] ?: SecureStoreRead.Absent
    }

    override fun write(slot: SecureSlot, value: String): WriteOutcome {
        if (unavailable) return WriteOutcome.Failed(UNAVAILABLE)
        items[slot] = SecureStoreRead.Found(value, StoredProtection.BACKGROUND_READABLE)
        return WriteOutcome.Ok
    }

    /** Best-effort, like every store: an unavailable one keeps what it has. */
    override fun migrateProtection(slot: SecureSlot): WriteOutcome {
        if (unavailable) return WriteOutcome.Failed(UNAVAILABLE)
        items[slot]?.let { items[slot] = it.copy(protection = StoredProtection.BACKGROUND_READABLE) }
        return WriteOutcome.Ok
    }

    /** Deleting an absent item is a no-op; an unavailable store has nothing it can delete. */
    override fun delete(slot: SecureSlot): WriteOutcome {
        if (unavailable) return WriteOutcome.Failed(UNAVAILABLE)
        items.remove(slot)
        return WriteOutcome.Ok
    }

    private companion object {
        const val UNAVAILABLE = "in-memory store constructed unavailable"
    }
}
