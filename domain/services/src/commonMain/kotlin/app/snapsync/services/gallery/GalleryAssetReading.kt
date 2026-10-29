package app.snapsync.services.gallery

import app.snapsync.data.gallery.assetId
import app.snapsync.data.gallery.facts
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.format.TimestampText
import app.snapsync.model.ImportResult
import app.snapsync.model.LibraryResource
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.Resource
import app.snapsync.model.uploadKey
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.ResourceKind

/**
 * Every original resource of [assets] as the upload cycle's [Resource]: keyed by [uploadKey] under its role, carrying
 * what its ledger row records — the capture date, the original name, the type — and the platform's handle riding along
 * uninterpreted for the uploader. What the asset IS is not carried: the selection decided on the typed facts before
 * any resource was read.
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
