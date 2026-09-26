package app.snapsync.services.identity

import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.StoredProtection
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.SecureStore
import app.snapsync.model.DeviceIdentityRole

/**
 * A real [PersistedDeviceIdentity] resolving to [id]: over an empty [store] it mints the platform's stable id, which is
 * [id]. For this module's tests, which cannot reach the fake module's in-memory store.
 */
internal fun identityOf(id: String, store: SecureStore = MapSecureStore()): PersistedDeviceIdentity =
    PersistedDeviceIdentity(DeviceIdentityRole.MINTING, store, PlatformDeviceId { id })

/** A map-backed secure store that counts its reads; [unavailable] answers as a locked device's does. */
internal class MapSecureStore(private val unavailable: Boolean = false) : SecureStore {
    private val items = mutableMapOf<SecureSlot, String>()
    var reads = 0
        private set

    override fun read(slot: SecureSlot): SecureStoreRead {
        reads++
        if (unavailable) return SecureStoreRead.Unavailable("locked")
        return items[slot]?.let { SecureStoreRead.Found(it, StoredProtection.BACKGROUND_READABLE) } ?: SecureStoreRead.Absent
    }

    override fun write(slot: SecureSlot, value: String): WriteOutcome =
        if (unavailable) WriteOutcome.Failed("locked") else WriteOutcome.Ok.also { items[slot] = value }

    override fun migrateProtection(slot: SecureSlot): WriteOutcome = WriteOutcome.Ok
    override fun delete(slot: SecureSlot): WriteOutcome = WriteOutcome.Ok.also { items.remove(slot) }
}
