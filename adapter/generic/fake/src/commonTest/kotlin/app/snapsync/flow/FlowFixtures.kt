package app.snapsync.flow

import app.snapsync.fake.fixedClock
import app.snapsync.fake.inMemoryDatabases
import app.snapsync.fake.inMemoryFiles
import app.snapsync.feature.download.DownloadController
import app.snapsync.model.AssetId
import app.snapsync.model.AssetPresence
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.model.StartResult
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.GalleryImport
import app.snapsync.services.backend.EventUnionSource
import app.snapsync.services.config.ConfigService
import app.snapsync.services.downloads.DownloadJobs
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.gallery.GalleryImporter
import app.snapsync.services.gallery.ImportedAssetPresence
import app.snapsync.services.staging.StagingService
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Instant

// The flows' collaborators, as the composition builds them: the REAL download controller and its services, over ports
// that do nothing — these tests are about a flow's ordering, and none of them downloads or imports.

/** A download session that holds nothing and starts nothing. */
private object InertDownload : Download {
    override fun listen(handlers: DownloadHandlers) = Unit
    override fun start(url: String, tag: String): StartResult = StartResult.NotStarted
    override suspend fun cancelAll() = Unit
}

/** A photo library no flow test imports into. */
private object NoImports : GalleryImport {
    override suspend fun import(request: ImportRequest): ImportResult = ImportResult.Failed("the flow tests never import")
}

/** A library that can tell nothing about an imported asset — no flow test adjudicates one. */
private object UnknownPresence : ImportedAssetPresence {
    override suspend fun presence(localIds: Set<AssetId>): Map<AssetId, AssetPresence> =
        localIds.associateWith { AssetPresence.UNKNOWN }
}

/** The real download controller over [union], an in-memory store and inert transfer and import ports. */
internal fun CoroutineScope.flowDownloadController(union: EventUnionSource): DownloadController {
    val staging = StagingService(inMemoryFiles())
    return DownloadController(
        union = union,
        store = DownloadService(inMemoryDatabases()),
        jobs = DownloadJobs(this, staging, InertDownload, onStaged = { _, _, _ -> }),
        importer = GalleryImporter(NoImports, staging),
        presence = UnknownPresence,
        eventAlbum = { null },
        stagedBytes = staging,
        myDeviceId = "DEV",
        downloadEnabled = { true },
    )
}

/** The real membership service over an empty in-memory shared area — no membership. */
internal fun noMembership(): ConfigService = ConfigService(inMemoryFiles(), fixedClock(Instant.parse("2026-07-09T12:00:00Z")))
