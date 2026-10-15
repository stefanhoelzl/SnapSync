package app.snapsync.mock

import app.snapsync.levers.StoreFault
import app.snapsync.ports.SecureSlot
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection
import app.snapsync.ports.WriteOutcome

/**
 * The honest [SecureStore]: one value per slot in [items] (the caller's own cell), held to the same contract as
 * the Keychain and the simulator's App-Group file store (`SecureStoreContract`).
 *
 * [refusals] are asked before every call: a reason they answer refuses the call — a read answers `Unavailable` ("I could
 * not look"), a write or a delete `Failed` — and changes nothing. What a device not unlocked since it restarted
 * presents for every call, and what an operator's fault presents for one.
 */
internal class InMemorySecureStore(
    private val items: MutableMap<SecureSlot, SecureStoreRead.Found>,
    private val refusals: StoreRefusals<SecureSlot>,
) : SecureStore {

    override fun read(slot: SecureSlot): SecureStoreRead =
        refusals.of(slot, StoreFault.READ)?.let { SecureStoreRead.Unavailable(it) } ?: items[slot] ?: SecureStoreRead.Absent

    override fun write(slot: SecureSlot, value: String): WriteOutcome {
        refusals.of(slot, StoreFault.WRITE)?.let { return WriteOutcome.Failed(it) }
        items[slot] = SecureStoreRead.Found(value, StoredProtection.BACKGROUND_READABLE)
        return WriteOutcome.Ok
    }

    /** Deleting an absent item is a no-op; a refused store has nothing it can delete. */
    override fun delete(slot: SecureSlot): WriteOutcome {
        refusals.of(slot, StoreFault.WRITE)?.let { return WriteOutcome.Failed(it) }
        items.remove(slot)
        return WriteOutcome.Ok
    }
}
