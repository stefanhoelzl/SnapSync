package app.snapsync.world

import app.snapsync.download.ImportResult
import app.snapsync.download.PhotoDownloadJobs
import app.snapsync.download.PhotoLibraryImporter
import app.snapsync.downloadstore.AssetRef
import app.snapsync.downloadstore.PendingDownload
import app.snapsync.downloadstore.StagedResource
import app.snapsync.gallery.DeviceManifestAsset
import app.snapsync.gallery.DeviceManifestStore
import app.snapsync.gallery.InMemoryRawAssetSource
import app.snapsync.gallery.RawAsset
import app.snapsync.gallery.RawResource
import app.snapsync.gallery.ResourceRole
import app.snapsync.gallery.normalizeAssetId

/**
 * An operator-driven, inspectable [PhotoDownloadJobs] (capability `harness-world-model`). `enqueue`
 * records the pending downloads; the world's `stageAllDownloads()` operator action resolves each
 * pending resource against the store and drives the controller's staging callback. A download that is
 * never staged simply stays PENDING for retry — there is **no** terminal transfer-failure (matching the
 * shipped no-`DownloadError` posture).
 */
class FakePhotoDownloadJobs : PhotoDownloadJobs {
    /** Inspection: every resource ever enqueued (idempotent re-enqueues append; dedup by key at read). */
    val enqueued = mutableListOf<PendingDownload>()
    var cancelled: Boolean = false

    override suspend fun enqueue(downloads: List<PendingDownload>) {
        enqueued += downloads
    }

    override suspend fun cancelAll() {
        cancelled = true
        enqueued.clear()
    }

    /** The currently-pending downloads, de-duplicated by (ref, resourceKey) for staging. */
    fun pending(): List<PendingDownload> =
        enqueued.distinctBy { it.ref to it.resource.resourceKey }
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
        gallery.set(gallery.walkAll() + newAsset)
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
