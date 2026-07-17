package app.snapsync.feature.download

import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.ImportResult
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.ports.PhotoLibraryImporter

import app.snapsync.ports.AssetRef
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.PlannedResource
import app.snapsync.model.invocation
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
    // The download arm runs only when the current membership's participation direction includes download
    // (capability `join-event`): an upload-only membership performs no reconcile at ANY trigger. Injected
    // as a plain predicate so this capability gains no config dependency; the composition root binds it to
    // `EventConfig.direction.includesDownload`. This is the SINGLE choke point — every trigger (join,
    // foreground, push) funnels through `reconcile`, so the gate lives here and not in the untested shell.
    // It is orthogonal to the push receiver's active-event guard (which answers "is this push for my event").
    //
    // **Three-valued, and required.** `true` = joined and the direction includes download; `false` = joined
    // but upload-only; `null` = **no membership at all**. Those last two are different answers and neither
    // enables the arm — collapsing them is not a nicety. This was `() -> Boolean = { true }`, bound at the
    // root with a `?: true`, so "we have no membership" resolved to "download freely": the same `?: true`
    // shape `UploadArm`'s KDoc blames for starting an upload producer for an event that did not exist. It was
    // unreachable only because every caller happened to pass a config-derived event id — a property of the
    // callers, not of the gate. The default is gone for the same reason the cutoff and the reconcile have
    // none: a permissive default on a safety gate is how a caller ships without one.
    private val downloadEnabled: () -> Boolean?,
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
    suspend fun reconcile(eventId: String) = log.invocation("reconcile", params = "eventId=$eventId") {
        // `!= true` covers BOTH non-answers: an upload-only membership (`false`) and no membership at all
        // (`null`). Neither enables the arm, and neither is inferred from the other.
        if (downloadEnabled() != true) {
            // Upload-only membership, or none: skip discovery entirely (no union fetch, no enqueue, no import).
            log.i { "reconcile skipped — this membership does not download" }
            return@invocation
        }
        val assets = union.union(eventId).getOrElse {
            log.w(it) { "union fetch failed — keeping last state" }
            return@invocation
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
    suspend fun onResourceStaged(ref: AssetRef, resourceKey: String, stagedPath: String) =
        log.invocation("onResourceStaged", params = "key=$resourceKey") {
            mutex.withLock {
                store.markStaged(ref, resourceKey, stagedPath)
                importReadyLocked()
            }
        }

    /** Import every asset whose resources are all staged and that is not yet imported. */
    suspend fun importReady() = log.invocation("importReady") { mutex.withLock { importReadyLocked() } }

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
    suspend fun onLeaveOrSwitch() = log.invocation("onLeaveOrSwitch") {
        mutex.withLock {
            jobs.cancelAll()
            store.pruneNonTerminal()
        }
    }
}
