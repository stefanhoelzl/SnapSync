package app.snapsync.services.gallery

import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.time.Timestamp
import app.snapsync.model.AssetFacts
import app.snapsync.model.ImportResult
import app.snapsync.model.LibraryResource
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.RESOURCE_META_IS_EDITED
import app.snapsync.model.RESOURCE_META_IS_SCREENSHOT
import app.snapsync.model.RESOURCE_META_IS_SCREEN_RECORDING
import app.snapsync.model.RESOURCE_META_IS_VIDEO
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.RESOURCE_META_PIXEL_AREA
import app.snapsync.model.Resource
import app.snapsync.model.ResourceRole
import app.snapsync.model.TimestampText
import app.snapsync.model.uploadKey
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.ResourceKind

/** The app's facts of the asset the platform describes. */
fun GalleryAsset.facts(): AssetFacts =
    AssetFacts(
        assetId = id.assetId(),
        captureDate = created?.let { CaptureDate.Dated(Timestamp.of(it)) } ?: CaptureDate.Undated,
        isScreenshot = isScreenshot,
        isScreenRecording = isScreenRecording,
        isVideo = isVideo,
        isEdited = isEdited,
        pixelArea = pixelArea,
    )

/**
 * Every original resource of [assets] as the upload cycle's [Resource]: keyed by [uploadKey] under its role, its
 * asset's facts carried in its metadata for the one admission to decide on, and the platform's handle riding along
 * uninterpreted for the uploader.
 */
fun resourcesFrom(assets: List<GalleryAssetResources>): List<Resource> = assets.flatMap { asset ->
    val facts = asset.asset.facts()
    asset.resources.map { resource ->
        Resource(
            filename = uploadKey(facts.assetId, resource.kind.role(), resource.originalFilename),
            assetId = facts.assetId,
            contentType = resource.contentType,
            metadata = mapOf(
                RESOURCE_META_CREATION_DATE to TimestampText.captureText(facts.captureDate),
                RESOURCE_META_ORIGINAL_FILENAME to resource.originalFilename,
                RESOURCE_META_MIME to resource.contentType,
                RESOURCE_META_IS_SCREENSHOT to facts.isScreenshot.toString(),
                RESOURCE_META_IS_SCREEN_RECORDING to facts.isScreenRecording.toString(),
                RESOURCE_META_IS_VIDEO to facts.isVideo.toString(),
                RESOURCE_META_IS_EDITED to facts.isEdited.toString(),
                RESOURCE_META_PIXEL_AREA to facts.pixelArea?.toString().orEmpty(),
            ),
            data = GalleryResourceRef(resource.handle),
        )
    }
}

/** The place a resource of this kind takes in its asset's upload: a still or a video is its primary, motion is live. */
fun ResourceKind.role(): ResourceRole = when (this) {
    ResourceKind.PHOTO, ResourceKind.VIDEO -> ResourceRole.PRIMARY
    ResourceKind.PAIRED_VIDEO -> ResourceRole.LIVE
}

/** What the platform's import outcome means to the app: the same outcome, its assets under their app ids. */
fun ImportOutcome.importResult(): ImportResult = when (this) {
    is ImportOutcome.Imported -> ImportResult.Imported(created.assetId())
    is ImportOutcome.Failed -> ImportResult.Failed(message, consumedResources, placeholder?.assetId())
}

/** The library's own resource as the app carries it ([LibraryResource]): the gallery port's [handle], held opaquely. */
data class GalleryResourceRef(val handle: GalleryResourceHandle) : LibraryResource

/** The gallery port's handle of [this] resource, or `null` for one the library did not read. */
fun LibraryResource?.galleryHandle(): GalleryResourceHandle? = (this as? GalleryResourceRef)?.handle
