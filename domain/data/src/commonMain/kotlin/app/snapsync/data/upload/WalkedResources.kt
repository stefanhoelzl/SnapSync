package app.snapsync.data.upload

import app.snapsync.data.gallery.facts
import app.snapsync.data.sharing.DiscoveryRead
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.sharing.AdmittedAsset
import app.snapsync.domain.upload.FoundResource
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadKey
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.ResourceKind

/** The library resource an upload sends from — the platform's own handle, carried uninterpreted to a tier. */
class SendFrom(val handle: GalleryResourceHandle)

/** A resource a walk read: what its row records, and where its upload sends from. */
class WalkedResource(val found: FoundResource, val from: SendFrom)

/** Whether a row's photo was found again: where to send from, gone from the library or the selection, or unreadable. */
sealed interface KeyLookup {
    class Found(val from: SendFrom) : KeyLookup

    data object Gone : KeyLookup

    data object NotReadable : KeyLookup
}

/**
 * **The member's own photos' resources, as the upload records and sends them** — every original resource of an asset
 * keyed under its role, carrying what its row records (the capture date, the original name, the type) and where its
 * bytes send from. What the asset IS is not carried: the selection decided on the typed facts before any resource was
 * read. A library that cannot be read now yields none, which uploads nothing rather than something wrong.
 */
class OwnPhotoResources(private val photos: OwnPhotos) {

    /** The resources of [assets], from the read that admitted them: in hand for a selection, one request for a walk. */
    suspend fun of(read: DiscoveryRead.Discovered, assets: List<AdmittedAsset>): List<WalkedResource> =
        when (val resources = read.resourcesOf(assets)) {
            is GalleryRead.Read -> walked(resources.value)
            GalleryRead.NotReadable -> emptyList()
        }

    /** [key]'s resource looked up again, under the read the grant allows now. */
    suspend fun find(key: UploadKey): KeyLookup = when (val read = photos.resolve(setOf(key.asset))) {
        is GalleryRead.Read -> found(walked(read.value).firstOrNull { it.found.key == key })
        GalleryRead.NotReadable -> KeyLookup.NotReadable
    }

    private fun found(resource: WalkedResource?): KeyLookup = when (resource) {
        null -> KeyLookup.Gone
        is WalkedResource -> KeyLookup.Found(resource.from)
    }

    private fun walked(assets: List<GalleryAssetResources>): List<WalkedResource> = assets.flatMap { asset ->
        val facts = asset.asset.facts()
        asset.resources.map { resource ->
            WalkedResource(
                FoundResource(
                    UploadKey.of(facts.assetId, roleOf(resource.kind), resource.originalFilename),
                    RowDetail(facts.captureDate, resource.contentType, resource.originalFilename),
                ),
                SendFrom(resource.handle),
            )
        }
    }

    /** The place a resource of this kind takes: a still or a video is its asset's primary, motion is live. */
    private fun roleOf(kind: ResourceKind): ResourceRole = when (kind) {
        ResourceKind.PHOTO, ResourceKind.VIDEO -> ResourceRole.PRIMARY
        ResourceKind.PAIRED_VIDEO -> ResourceRole.LIVE
    }
}
