package app.snapsync.services.gallery

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.device.DeviceId
import app.snapsync.format.ResourceRoleText
import app.snapsync.format.TimestampText
import app.snapsync.model.AssetRef
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.model.ReceivedPhotoName
import app.snapsync.model.StagedResource
import app.snapsync.ports.GalleryImport
import app.snapsync.ports.GalleryImportRequest
import app.snapsync.ports.ImportFile
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.ResourceKind
import app.snapsync.services.staging.StagingService

/**
 * **A foreign asset's import from staging**: the one translation of the app's import request into the library's. Its
 * staged resources, whose paths the download store keeps relative to the shared area, are located on this device; each
 * is named with its ref's mark ([ReceivedPhotoName]) and given the [ResourceKind] its role and type say; and the ref
 * travels as the request's correlation ([correlation]), which comes back with both of the library's deliveries.
 *
 * Locating is this service's, not the caller's, because a staged path only means something on the device that staged
 * it — a restored device's container moved — and the library must be handed the platform's path. A shared area that
 * cannot be reached THROWS ([StagingService.locate]): importing from a directory nobody chose would lose the photo
 * without a trace, so the caller sees a throw, exactly as it sees one from the library call itself.
 */
class GalleryImporter(private val gallery: GalleryImport, private val staging: StagingService) {

    /**
     * Import [request], whose staged paths are relative. What the library reports is the answer — see
     * [GalleryImport.import] for what a throw does and does not promise. A request with no resource the library takes
     * creates nothing and is refused here for good: no later pass could import it, so its row settles.
     */
    suspend fun import(request: ImportRequest): ImportResult {
        val files = request.resources.mapNotNull { resource ->
            resource.kind()?.let { kind ->
                ImportFile(
                    path = staging.locate(resource.stagedPath),
                    contentType = resource.contentType,
                    kind = kind,
                    name = ReceivedPhotoName.mark(resource.originalFilename, resource.resourceKey, request.ref),
                )
            }
        }
        if (files.isEmpty()) {
            return ImportResult.Failed(
                "no resource of ${request.ref.sourceAssetId} is a photo or a video",
                consumedResources = true,
            )
        }
        return gallery.import(
            GalleryImportRequest(
                correlation = request.ref.correlation(),
                files = files,
                created = TimestampText.parseOrNull(request.creationDate)?.at,
                album = request.album?.let(::PlatformAlbumId),
            ),
        ).importResult()
    }
}

/**
 * What a staged resource is to the library: its live motion a paired video, its primary a photo or a video by its type,
 * and anything else nothing the library takes.
 */
private fun StagedResource.kind(): ResourceKind? = when (role) {
    ResourceRoleText.of(ResourceRole.LIVE) -> ResourceKind.PAIRED_VIDEO
    ResourceRoleText.of(ResourceRole.PRIMARY) -> when {
        contentType.startsWith("image/") -> ResourceKind.PHOTO
        contentType.startsWith("video/") -> ResourceKind.VIDEO
        else -> null
    }
    else -> null
}

/** The correlation an import of [this] ref carries: `<sourceDeviceId>/<sourceAssetId>` — an app id holds no `/`. */
fun AssetRef.correlation(): String = "$sourceDeviceId/${sourceAssetId.value}"

/** The ref an import's [correlation] names: [correlation] read backwards. */
fun assetRefOf(correlation: String): AssetRef =
    AssetRef(
        requireNotNull(DeviceId.parse(correlation.substringBeforeLast('/'))) { "not a correlation: '$correlation'" },
        requireNotNull(AssetId.parse(correlation.substringAfterLast('/'))) { "not a canonical asset id" },
    )
