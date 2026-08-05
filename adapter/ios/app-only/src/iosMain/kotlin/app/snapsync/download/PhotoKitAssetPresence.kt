package app.snapsync.download

import app.snapsync.model.AssetPresence
import app.snapsync.model.denormalizeAssetId
import app.snapsync.model.normalizeAssetId
import app.snapsync.ports.ImportedAssetPresence
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Photos.PHAsset

/**
 * The full-access [ImportedAssetPresence] (capability `photo-download`): asks PhotoKit, by identifier,
 * which of the assets this device created still exist.
 *
 * **Only ever composed for a full grant.** Under a partial grant the answer would be wrong in the
 * dangerous direction — a fetch sees only the user's selection, so an asset created before a downgrade
 * reads as missing, and acting on that clears a live marker and orphans a real photo. Composition binds
 * the selection-backed source there instead; this class simply never runs. It therefore reports
 * [AssetPresence.ABSENT] freely, which is exactly what a whole-library view is allowed to do.
 *
 * **The hop is not tidiness.** `fetchAssetsWithLocalIdentifiers` is a synchronous XPC round-trip into
 * `photolibraryd` — it blocks the calling thread, and `withTimeoutOrNull` cannot rescue it, because
 * cancellation is cooperative and the thread is inside the call. That is the same reason `IosDiscovery`
 * hops rather than bounds (its forcing proof: build 521 died on main inside
 * `fetchPersistentChangesSinceToken`, 2026-07-26), and it is why the caller also keeps this off the
 * download controller's lock. Kotlin/Native has no `Dispatchers.IO`, hence [Dispatchers.Default].
 *
 * Identifier form: the store speaks the normalized `/`→`_` id; PhotoKit speaks the raw
 * `{UUID}/L0/NNN`. The conversion is exact in both directions (a `localIdentifier` never contains `_`),
 * and it is the same pair the event-album add path already relies on.
 */
@OptIn(ExperimentalForeignApi::class)
class PhotoKitAssetPresence : ImportedAssetPresence {

    override suspend fun presence(localIds: Set<String>): Map<String, AssetPresence> {
        if (localIds.isEmpty()) return emptyMap()
        return withContext(Dispatchers.Default) {
            val found = PHAsset.fetchAssetsWithLocalIdentifiers(localIds.map(::denormalizeAssetId), null)
            val present = buildSet {
                var i = 0uL
                while (i < found.count) {
                    (found.objectAtIndex(i) as? PHAsset)?.let { add(normalizeAssetId(it.localIdentifier)) }
                    i++
                }
            }
            // Every id asked about gets an entry: a whole-library view may say ABSENT, and saying it
            // explicitly is what lets the caller clear a stale marker rather than leave the row stuck.
            localIds.associateWith { if (it in present) AssetPresence.PRESENT else AssetPresence.ABSENT }
        }
    }
}
