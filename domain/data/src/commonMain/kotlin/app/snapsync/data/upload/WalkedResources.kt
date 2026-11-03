package app.snapsync.data.upload

import app.snapsync.data.gallery.facts
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.upload.FoundResource
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadKey
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.ResourceKind

/** A resource a walk read: what its row records, and the library's handle its upload sends from. */
class WalkedResource(val found: FoundResource, val handle: GalleryResourceHandle)

/**
 * Every original resource of a read's assets as the upload records and sends it: keyed under its role, carrying what
 * its row records — the capture date, the original name, the type — and the platform's handle, uninterpreted. What the
 * asset IS is not carried: the selection decided on the typed facts before any resource was read.
 */
object WalkedResources {
    fun of(assets: List<GalleryAssetResources>): List<WalkedResource> = assets.flatMap { asset ->
        val facts = asset.asset.facts()
        asset.resources.map { resource ->
            WalkedResource(
                FoundResource(
                    UploadKey.of(facts.assetId, roleOf(resource.kind), resource.originalFilename),
                    RowDetail(facts.captureDate, resource.contentType, resource.originalFilename),
                ),
                resource.handle,
            )
        }
    }

    /** The place a resource of this kind takes: a still or a video is its asset's primary, motion is live. */
    private fun roleOf(kind: ResourceKind): ResourceRole = when (kind) {
        ResourceKind.PHOTO, ResourceKind.VIDEO -> ResourceRole.PRIMARY
        ResourceKind.PAIRED_VIDEO -> ResourceRole.LIVE
    }
}
