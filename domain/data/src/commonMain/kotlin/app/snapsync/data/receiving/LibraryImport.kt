package app.snapsync.data.receiving

import app.snapsync.data.Parsed
import app.snapsync.data.gallery.assetId
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.ImportReport
import app.snapsync.domain.receiving.ReceivedMedia
import app.snapsync.domain.receiving.ReceivedPhotoName
import app.snapsync.domain.receiving.StagedDownload
import app.snapsync.domain.receiving.StagedResource
import app.snapsync.domain.receiving.UnconfirmedImport
import app.snapsync.format.ResourceRoleText
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.DbResult
import app.snapsync.ports.GalleryImport
import app.snapsync.ports.GalleryImportRequest
import app.snapsync.ports.ImportFile
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind

/**
 * **A received photo's import from staging**, over the [GalleryImport] port: the one translation of a
 * [StagedDownload] into the library's request. Each staged resource is located on this device — a staged path only
 * means something on the device that staged it — named with its ref's mark ([ReceivedPhotoName]) and given the
 * [ResourceKind] its role and type say; the ref travels as the request's correlation ([ImportCorrelation]), which
 * comes back with both of the library's deliveries.
 */
class LibraryImport(private val gallery: GalleryImport, private val staging: StagingArea) {

    /**
     * Import [download], filed into [album] in the same transaction (`null`: none). A download with no resource the
     * library takes creates nothing and is refused for good — consumed, since no later pass could import it. A shared
     * area that cannot be reached submits nothing, and a later pass imports it again.
     */
    suspend fun import(download: StagedDownload, album: String?): ImportReport = files(download).let { files ->
        when (files) {
            is Files.Gathered -> requested(download, files.files, album?.let(::PlatformAlbumId))
            is Files.Unreachable -> ImportReport.Failed("the shared area is unreachable: ${files.detail}", false, null)
        }
    }

    private suspend fun requested(
        download: StagedDownload,
        files: List<ImportFile>,
        album: PlatformAlbumId?,
    ): ImportReport =
        when (files.isEmpty()) {
            true -> ImportReport.Failed(
                "no resource of ${download.ref.sourceAssetId} is a photo or a video",
                true,
                null,
            )
            false -> reportOf(
                gallery.import(
                    GalleryImportRequest(ImportCorrelation.of(download.ref), files, download.captured.at(), album),
                ),
            )
        }

    private fun files(download: StagedDownload): Files =
        download.resources.mapNotNull { resource -> resource.kind()?.let { resource to it } }
            .fold<Pair<StagedResource, ResourceKind>, Files>(Files.Gathered(emptyList())) { held, (resource, kind) ->
                held.with(resource, kind, staging.locate(resource.stagedPath), download.ref)
            }

    /** The located files of a request so far, or the reason one could not be located. */
    private sealed interface Files {
        fun with(
            resource: StagedResource,
            kind: ResourceKind,
            located: app.snapsync.data.receiving.Located,
            ref: AssetRef,
        ): Files

        class Gathered(val files: List<ImportFile>) : Files {
            override fun with(
                resource: StagedResource,
                kind: ResourceKind,
                located: app.snapsync.data.receiving.Located,
                ref: AssetRef,
            ): Files =
                when (located) {
                    is app.snapsync.data.receiving.Located.At ->
                        Gathered(
                            files + ImportFile(located.platformPath, resource.contentType, kind, resource.marked(ref)),
                        )
                    is app.snapsync.data.receiving.Located.Unreachable -> Unreachable(located.detail)
                }
        }

        class Unreachable(val detail: String) : Files {
            override fun with(
                resource: StagedResource,
                kind: ResourceKind,
                located: app.snapsync.data.receiving.Located,
                ref: AssetRef,
            ): Files = this
        }
    }
}

/** What the library reported of an import, as the domain reads it. */
fun reportOf(outcome: ImportOutcome): ImportReport = when (outcome) {
    is ImportOutcome.Imported -> ImportReport.Imported(outcome.created.assetId())
    is ImportOutcome.Failed -> ImportReport.Failed(
        outcome.message,
        outcome.consumedResources,
        outcome.placeholder?.assetId(),
    )
}

/**
 * An import's correlation — `<sourceDeviceId>/<sourceAssetId>`, opaque to the platform and handed back with both of
 * the import's deliveries. An app id holds no `/`.
 */
object ImportCorrelation {
    fun of(ref: AssetRef): String = "${ref.sourceDeviceId}/${ref.sourceAssetId.value}"

    /** The ref [correlation] names, as a list of one — or of none for one no import of this build carried. */
    fun refs(correlation: String): List<AssetRef> =
        refOf(correlation.substringBeforeLast('/'), correlation.substringAfterLast('/'))
}

/**
 * The PhotoKit blocks' writes for an import, persisted INLINE on the platform's delivering thread: the placeholder
 * inside the change block — before the new asset can be observed by sharing, so the echo is closed even if the
 * completion lands later or fails — and the settle from the completion, which runs even when the requester is gone.
 * Non-suspending, as the blocks cannot call a suspending function; each write carries its own guard, so none takes the
 * store's lock.
 */
class ImportMarkerWrites(private val file: DownloadDatabaseFile) {

    /**
     * The placeholder [created] for the ref [correlation] names, written before the asset's creation commits. A write
     * that lands on no row means the row was pruned out from under this import: the asset being created has no
     * suppression handle, and this device would share a received photo back — logged at `Error`, so it reaches crash
     * reporting: this line is the only evidence.
     */
    fun placeholder(correlation: String, created: PlatformAssetId): MarkerWrite =
        ImportCorrelation.refs(correlation).map { ref -> placed(ref, created) }.firstOrNull()
            ?: MarkerWrite.Unknown(correlation)

    /** The import's outcome: an imported asset confirms its marker, a failed one clears the placeholder it left. */
    fun settled(correlation: String, outcome: ImportOutcome): MarkerWrite =
        ImportCorrelation.refs(correlation).map { ref -> settledFor(ref, reportOf(outcome)) }.firstOrNull()
            ?: MarkerWrite.Unknown(correlation)

    private fun placed(ref: AssetRef, created: PlatformAssetId): MarkerWrite =
        marker(file.execute { q -> recordMarker(q, ref, created.assetId()) }.also { reported(it, ref) })

    private fun settledFor(ref: AssetRef, report: ImportReport): MarkerWrite = when (report) {
        is ImportReport.Imported -> marker(file.execute { q -> confirm(q, UnconfirmedImport(ref, report.created)) })
        is ImportReport.Failed -> cleared(ref, report.placeholder)
    }

    /** A failed change clears the placeholder it left — and one refused before its block ran left none. */
    private fun cleared(ref: AssetRef, placeholder: AssetId?): MarkerWrite =
        listOfNotNull(placeholder).map { id -> marker(file.execute { q -> clear(q, UnconfirmedImport(ref, id)) }) }
            .firstOrNull() ?: MarkerWrite.Applied

    private fun reported(placed: DbResult<Boolean>, ref: AssetRef) = when (placed) {
        is DbResult.Ok -> landed(placed.value, ref)
        is DbResult.Failed -> Unit
    }

    private fun landed(onRow: Boolean, ref: AssetRef) = when (onRow) {
        true -> Unit
        false -> log.unlabelled.e(
            "an import's marker landed on no row: its row was pruned mid-import",
            "asset" of ref.sourceAssetId,
        )
    }
}

private val log = Log("ImportMarkerWrites")

/** What a PhotoKit block's write came to. */
sealed interface MarkerWrite {
    data object Applied : MarkerWrite

    /** The guard matched no row: for a placeholder, the row was pruned; for a settle, it moved on. */
    data object NoRow : MarkerWrite

    class Failed(val detail: String) : MarkerWrite

    /** The correlation names no ref this build's imports carry. */
    class Unknown(val correlation: String) : MarkerWrite
}

private fun marker(result: DbResult<Boolean>): MarkerWrite = when (result) {
    is DbResult.Ok -> when (result.value) {
        true -> MarkerWrite.Applied
        false -> MarkerWrite.NoRow
    }
    is DbResult.Failed -> MarkerWrite.Failed(result.detail)
}

/** What a staged resource is to the library, or `null` when it is nothing the library takes. */
private fun StagedResource.kind(): ResourceKind? = when (media()) {
    ReceivedMedia.PHOTO -> ResourceKind.PHOTO
    ReceivedMedia.VIDEO -> ResourceKind.VIDEO
    ReceivedMedia.PAIRED_VIDEO -> ResourceKind.PAIRED_VIDEO
    ReceivedMedia.NONE -> null
}

/** What the library takes this resource as — nothing, for a role no build names. */
private fun StagedResource.media(): ReceivedMedia = when (val named = roleOf(role)) {
    is Parsed.Ok -> ReceivedMedia.of(named.value, contentType)
    is Parsed.Refused -> ReceivedMedia.NONE
}

private fun roleOf(text: String): Parsed<ResourceRole> =
    ResourceRoleText.parse(text)?.let { Parsed.Ok(it) } ?: Parsed.Refused("no build names the role $text")

private fun StagedResource.marked(ref: AssetRef): String = ReceivedPhotoName.mark(
    originalFilename,
    resourceKey,
    ref,
)

private fun CaptureDate.at() = when (this) {
    is CaptureDate.Dated -> at.at
    CaptureDate.Undated -> null
}
