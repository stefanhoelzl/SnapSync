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
 * **The hop buys CONCURRENCY, not safety.** Keeping this off the main thread is not this seam's job:
 * the app's composition scope is a dedicated non-UI lane, so every adapter is off main whether it hops
 * or not (spec `module-architecture`, law "Dispatcher lanes are fixed by the composition"). What the
 * hop buys is that a stalled `photolibraryd` parks one `Dispatchers.Default` thread rather than the
 * **serial** composition lane — and that is what makes the caller's deliberate choice to adjudicate
 * outside the download controller's mutex mean anything. With the lane itself blocked, being off the
 * lock would buy nothing: no reconcile, import, leave or switch could run to want the lock in the
 * first place. Parking *some* thread is the only outcome on offer — `fetchAssetsWithLocalIdentifiers`
 * is a synchronous XPC round-trip that no timeout can abandon, because cancellation is cooperative and
 * the thread is inside the call — so the hop chooses which one it is. [Dispatchers.Default] rather
 * than an I/O pool because Kotlin/Native exposes no **public** `Dispatchers.IO` (established by
 * compile, not by reading the symbol table); expiry: a coroutines release that publishes it.
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
