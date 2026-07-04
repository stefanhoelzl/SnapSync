package app.snapsync.download

import app.snapsync.downloadstore.AssetRef
import app.snapsync.downloadstore.DownloadStore
import app.snapsync.downloadstore.PlannedResource
import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The device-side download/import orchestrator (capability `photo-download`). Reads the event-wide
 * union, selects **foreign** assets (`deviceId != myDeviceId`) not already imported, records them in
 * the [store], enqueues their resource downloads, and imports any asset whose resources are all staged.
 * It owns no transport or PhotoKit detail — those are the [jobs] and [importer] seams — so it is
 * exercised in `commonTest` with fakes. Network/union failures keep last-good state (never throw).
 */
class DownloadController(
    private val union: EventUnionSource,
    private val store: DownloadStore,
    private val jobs: PhotoDownloadJobs,
    private val importer: PhotoLibraryImporter,
    private val myDeviceId: String,
    private val log: Logger = Logger.withTag("DownloadController"),
) {

    // Serializes all store-mutating flows. Both join (`provisionEvent`) and foreground fire `reconcile`,
    // and downloads complete on the URLSession delegate — without this, two triggers can both find an
    // asset importable before either marks it IMPORTED and import it twice (observed on device).
    private val mutex = Mutex()

    /**
     * Discover + plan + enqueue + import, idempotently. Safe to call on join and on every foreground:
     * already-imported and already-planned assets are no-ops, and only not-yet-staged resources enqueue.
     */
    suspend fun reconcile(eventId: String) {
        val assets = union.union(eventId).getOrElse {
            log.w(it) { "union fetch failed — keeping last state" }
            return
        }
        mutex.withLock {
            var planned = 0
            for (asset in assets) {
                if (asset.deviceId == myDeviceId) continue // own contribution — already in this library
                val ref = AssetRef(asset.deviceId, asset.assetId)
                if (store.isImported(ref)) continue // delete-proof / cross-event dedup
                store.plan(ref, asset.creationDate, asset.resources.map {
                    PlannedResource(it.key, it.url, it.role, it.contentType, it.originalFilename)
                })
                planned++
            }
            log.i { "reconcile: ${assets.size} union asset(s), $planned foreign planned" }
            // Enqueue the not-yet-staged resources to the OS, then mark them in-flight so the status
            // line's download arrow can pulse (superseded once each stages). Idempotent: re-marking an
            // already-enqueued or already-staged resource is harmless (staged rows are excluded).
            val pending = store.pendingDownloads()
            jobs.enqueue(pending)
            pending.forEach { store.markEnqueued(it.ref, it.resource.resourceKey) }
            importReadyLocked()
        }
    }

    /**
     * A resource's bytes finished downloading and were moved to durable staging (called by the
     * background-`URLSession` delegate, possibly while backgrounded / on relaunch). Records it and
     * imports the asset if its set is now complete.
     */
    suspend fun onResourceStaged(ref: AssetRef, resourceKey: String, stagedPath: String) = mutex.withLock {
        store.markStaged(ref, resourceKey, stagedPath)
        importReadyLocked()
    }

    /** Import every asset whose resources are all staged and that is not yet imported. */
    suspend fun importReady() = mutex.withLock { importReadyLocked() }

    private suspend fun importReadyLocked() {
        for (importable in store.importableAssets()) {
            val ref = importable.ref
            when (val result = importer.import(ref, store.stagedResources(ref), importable.creationDate)) {
                is ImportResult.Imported -> {
                    store.markImported(ref, result.createdLocalId)
                    log.i { "imported foreign asset ${ref.sourceAssetId} as ${result.createdLocalId}" }
                }
                is ImportResult.Failed ->
                    log.w { "import deferred for ${ref.sourceAssetId}: ${result.message}" } // retried later
            }
        }
    }

    /** Leave/switch: cancel in-flight transfers and drop non-terminal rows (imported rows persist). */
    suspend fun onLeaveOrSwitch() = mutex.withLock {
        jobs.cancelAll()
        store.pruneNonTerminal()
    }
}
