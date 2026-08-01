package app.snapsync.world

import app.snapsync.ports.DownloadTask
import app.snapsync.ports.DownloadTransport
import app.snapsync.ports.DownloadTransportHost
import app.snapsync.ports.ImportResult
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.ports.TransferOutcome
import app.snapsync.ports.AssetRef
import app.snapsync.ports.StagedResource
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.importFilename
import app.snapsync.model.normalizeAssetId

/**
 * The operator-driven download **execution edge** (capability `harness-world-model`): a fake
 * [DownloadTransport] the world composes the **real** [app.snapsync.feature.download.QueuedPhotoDownloadJobs] over.
 *
 * Faking here rather than at `PhotoDownloadJobs` is the point. The layer above is the orchestration — the
 * bounded in-flight window, the transfer-description codec, the URL guard, and the transfer-integrity
 * check — and the world exists so the *real* stack runs against it, faking only the edge. Faking the jobs
 * instead left every one of those untested by the world and by `:test:integration`.
 *
 * [finish] mirrors the real `URLSession` delegate exactly, including the ordering the integrity check
 * depends on: ask whether the bytes may be staged, only then stage them, and report completion either way
 * (a download's completion callback follows its finish callback whether or not anything went wrong, which
 * is what frees the window slot).
 */
class FakeDownloadTransport(private val host: DownloadTransportHost) : DownloadTransport {

    /** Inspection: a transfer the real jobs started through this transport. */
    class Started(val url: String, val description: String) {
        var cancelled: Boolean = false
    }

    val started = mutableListOf<Started>()

    override fun start(url: String, description: String): DownloadTask? {
        val s = Started(url, description)
        started += s
        return object : DownloadTask {
            override fun cancel() {
                s.cancelled = true
                host.onCompleted(description, "cancelled")
            }
        }
    }

    /** The transfers still awaiting a finish, de-duplicated by description. */
    fun inFlight(): List<Started> = started.filterNot { it.cancelled }.distinctBy { it.description }

    /**
     * Deliver a finish for [description], exactly as the real delegate does. A rejected [outcome] leaves
     * the resource un-staged — which *is* the world's pending-for-retry state, not a new terminal one.
     */
    fun finish(description: String, outcome: TransferOutcome = HEALTHY) {
        if (host.accepts(description, outcome)) {
            host.destinationFor(description)?.let { host.onStaged(description, it) }
        }
        host.onCompleted(description, null)
    }

    companion object {
        /** An ordinary healthy transfer: `200`, no declared length — what staging assumes by default. */
        val HEALTHY: TransferOutcome =
            TransferOutcome(statusCode = 200, expectedBytes = -1L, receivedBytes = 1_024L)
    }
}

/**
 * A [PhotoLibraryImporter] that imports into the in-memory [gallery] (capability
 * `harness-world-model`) so **echo-suppression** is exercised end to end: the imported asset enters
 * gallery enumeration with an `assetId` byte-identical to the `createdLocalId` recorded in the download
 * store, so the own-device upload cycle (which reads `suppressedLocalIds()`) never re-uploads it. A
 * settable [failNextImport] yields `ImportResult.Failed` (non-terminal — the asset stays importable).
 */
class FakePhotoLibraryImporter(
    private val gallery: WorldGallery,
) : PhotoLibraryImporter {

    /** Inspection: the source refs imported. */
    val imported = mutableListOf<AssetRef>()

    /** Failure lever: the next import returns `Failed` (cleared after firing once). */
    var failNextImport: Boolean = false

    override suspend fun import(
        ref: AssetRef,
        resources: List<StagedResource>,
        creationDate: String,
    ): ImportResult {
        if (failNextImport) {
            failNextImport = false
            return ImportResult.Failed("forced")
        }
        // The suppression handle: byte-identical to the enumerator's normalized `assetId` form, so the
        // download store's `suppressedLocalIds()` matches the enumerated resource's `assetId`.
        val createdLocalId = normalizeAssetId("imported-${ref.sourceDeviceId}-${ref.sourceAssetId}")
        // Import into the gallery so the asset becomes enumerable (and thus visible to — but suppressed
        // from — the upload cycle).
        val newAsset = RawAsset(
            assetId = createdLocalId,
            creationDate = creationDate,
            rawResources = resources.map { staged ->
                RawResource(
                    type = if (staged.role == ResourceRole.LIVE.wire) 9L else 1L,
                    contentTypeUti = staged.contentType,
                    mimeContentType = staged.contentType,
                    // The SAME naming rule the iOS importer applies (`importFilename`), so the world
                    // cannot show a human name where a device would show a storage key.
                    originalFilename = importFilename(staged.originalFilename, staged.resourceKey),
                    handle = Unit,
                )
            },
        )
        gallery.set(gallery.current() + newAsset)
        imported += ref
        return ImportResult.Imported(createdLocalId)
    }
}

