package app.snapsync.services.identity

import app.snapsync.data.identity.MintingDeviceIdentity
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.SecureSlot
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection
import app.snapsync.ports.WriteOutcome
import app.snapsync.testids.testDeviceId

/**
 * A real [MintingDeviceIdentity] resolving to the fixture device [id] stands for ([testDeviceId]): over an empty
 * [store] it mints the platform's stable id, which is that device id. For this module's tests, which cannot reach the
 * fake module's in-memory store.
 */
@OptIn(AppProcessOnly::class)
internal fun identityOf(id: String, store: SecureStore = MapSecureStore()): MintingDeviceIdentity =
    MintingDeviceIdentity(store, PlatformDeviceId { testDeviceId(id).value })

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

    override fun delete(slot: SecureSlot): WriteOutcome = WriteOutcome.Ok.also { items.remove(slot) }
}
