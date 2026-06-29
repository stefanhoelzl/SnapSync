package app.snapsync.ios.upload

import app.snapsync.gallery.IosManifestStore
import app.snapsync.gallery.ManifestState
import app.snapsync.gallery.assetManifest
import app.snapsync.gallery.encodeToJson
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Photos.PHAsset

/**
 * The extension's per-cycle manifest side-channel (capability `asset-manifest`): for each backed-up
 * asset, ensure its manifest is generated, written PENDING to the App Group, and enqueued on the
 * background [ManifestUploadSession] — exactly once. Completeness is read-time (the list endpoint), so
 * order relative to the resources does not matter; this only guarantees the manifest is delivered.
 *
 * Per asset (keyed by [IosManifestStore] state): **DONE** → skip (the app marked it landed);
 * **ABSENT** (first discovery) → synthesize, write PENDING, enqueue one upload; **PENDING** with no
 * in-flight task on the session → re-enqueue exactly one (resurrect a stall), using only local task
 * state. The manifest never touches the engine, `createJob`, or the ledger.
 *
 * Wiring-only and untestable (PhotoKit + background `URLSession`, device-only); the synthesis + store
 * it drives are covered in `commonTest` and on device.
 */
@OptIn(ExperimentalForeignApi::class)
class IosManifestProducer(
    private val store: IosManifestStore,
    private val session: ManifestUploadSession,
    private val log: Logger,
) {

    suspend fun ensureManifests(eventId: String, host: String) {
        val inFlight = session.inFlightAssetIds()
        val assets = PHAsset.fetchAssetsWithOptions(null)
        var index = 0uL
        while (index < assets.count) {
            val asset = assets.objectAtIndex(index) as PHAsset
            index++
            val manifest = assetManifest(asset) ?: continue // no original resources → nothing to back up
            val assetId = manifest.assetId
            when (store.state(assetId)) {
                ManifestState.DONE -> continue
                ManifestState.ABSENT -> {
                    val url = store.writePending(assetId, manifest.encodeToJson()) ?: continue
                    session.enqueue(url, host, eventId, assetId)
                    log.i { "manifest $assetId enqueued (first discovery)" }
                }
                ManifestState.PENDING -> {
                    if (assetId in inFlight) continue // already uploading — do not duplicate
                    val url = store.pendingFileUrl(assetId)
                        ?: store.writePending(assetId, manifest.encodeToJson())
                        ?: continue
                    session.enqueue(url, host, eventId, assetId)
                    log.i { "manifest $assetId re-enqueued (stalled)" }
                }
            }
        }
    }
}
