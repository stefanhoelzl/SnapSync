package app.snapsync.world

import app.snapsync.download.DownloadTask
import app.snapsync.download.DownloadTransport
import app.snapsync.download.DownloadTransportHost
import app.snapsync.download.ImportResult
import app.snapsync.download.PhotoLibraryImporter
import app.snapsync.download.TransferOutcome
import app.snapsync.downloadstore.AssetRef
import app.snapsync.downloadstore.StagedResource
import app.snapsync.gallery.DeviceManifestAsset
import app.snapsync.gallery.DeviceManifestStore
import app.snapsync.gallery.InMemoryRawAssetSource
import app.snapsync.gallery.RawAsset
import app.snapsync.gallery.RawResource
import app.snapsync.gallery.ResourceRole
import app.snapsync.gallery.normalizeAssetId

/**
 * The operator-driven download **execution edge** (capability `harness-world-model`): a fake
 * [DownloadTransport] the world composes the **real** [app.snapsync.download.QueuedPhotoDownloadJobs] over.
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
    private val gallery: InMemoryRawAssetSource,
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
                    originalFilename = staged.originalFilename,
                    handle = Unit,
                )
            },
        )
        gallery.set(gallery.current() + newAsset)
        imported += ref
        return ImportResult.Imported(createdLocalId)
    }
}

/** The world's in-memory [DeviceManifestStore] for the composed `DeviceManifestProducer`. */
class InMemoryDeviceManifestStore : DeviceManifestStore {
    private var accumulator: List<DeviceManifestAsset> = emptyList()
    private var lastUploaded: String? = null

    override fun loadAccumulator(): List<DeviceManifestAsset> = accumulator
    override fun saveAccumulator(assets: List<DeviceManifestAsset>) {
        accumulator = assets
    }
    override fun loadLastUploaded(): String? = lastUploaded
    override fun saveLastUploaded(json: String) {
        lastUploaded = json
    }
}
