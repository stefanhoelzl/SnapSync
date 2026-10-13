@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.gallery

import app.snapsync.ios.qos.photoKitReadLane
import app.snapsync.ios.qos.qosLabel
import app.snapsync.model.WriteOutcome
import app.snapsync.objc.ObjCFailure
import app.snapsync.objc.checkedObjC
import app.snapsync.objc.objcBoundary
import app.snapsync.objc.objcCallback
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResource
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSPredicate
import platform.Foundation.NSURL
import platform.Photos.PHAsset
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionChangeRequest
import platform.Photos.PHAssetCollectionSubtypeAlbumRegular
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult
import platform.Photos.PHPhotoLibrary
import platform.UniformTypeIdentifiers.UTType
import kotlin.time.Instant

/**
 * The PhotoKit [GalleryReader] — every photo-library read and album write either process makes, and the only
 * place outside the app's own adapters that touches `PHAsset`, `PHAssetResource` or `PHAssetCollection`.
 *
 * **Ids cross as PhotoKit's own**: an asset's `localIdentifier`, an album's `localIdentifier`.
 *
 * **Facts are cheap; resources are not.** [assets] reads plain in-memory `PHAsset` properties;
 * `PHAssetResource.assetResourcesForAsset` — a synchronous XPC round-trip into `photolibraryd`, ~110 ms per asset
 * on an SE2 — runs only in [resources], for the assets the caller asks for, after one fetch by identifier for
 * all of them. Measured (rig probe, SE2, iOS 26.6, 2026-09-22): ~4.5 ms per request plus ~3.45 ms per photo.
 *
 * **Every read hops to [photoKitReadLane], for concurrency rather than for safety.** Off-main is the
 * composition's job (law "Dispatcher lanes are fixed by the composition"); the hop keeps a read from holding the
 * composition's **serial** lane while `assetsd` answers, at a pinned USER_INITIATED class: the calling thread's
 * QoS propagates over the XPC, and PhotoKit calls made at `QOS_CLASS_BACKGROUND` measured 6–7× slower on an SE2.
 * Forcing proof for keeping every PhotoKit call off main at all: build 521 died of the 10 s scene-update watchdog
 * (`0x8BADF00D`) on 2026-07-26 (iPhone11,2 / iOS 18.7.9) with `assetsd` wedged. No timeout can abandon a wedged
 * call — cancellation is cooperative and the thread is inside it — so the hop chooses where it parks.
 *
 * **Reads answer [GalleryRead.NotReadable] without touching PhotoKit** unless the grant is full or partial:
 * with no grant a fetch returns nothing for assets that exist, and fetching an asset collection under
 * `NOT_DETERMINED` raises iOS's permission dialog (measured, simulator, iOS 26.4, `tccd` logs
 * `AUTHREQ_PROMPTING`). Under a partial grant PhotoKit answers the selection, and the user-album walk returns
 * **no** albums, empty and without an error, even when a selected asset is in one (SE2, a real WhatsApp album).
 */
class IosGalleryReader(private val log: Logger = Logger.withTag("gallery")) : GalleryReader {

    override fun access(): LibraryGrant = currentPhotoPermission()

    /**
     * Narrowed by [predicateFor] — [filter]'s date range, widened. Both QoS classes on the one line: the caller's, and the lane's
     * the PhotoKit calls were issued at.
     */
    override suspend fun assets(filter: AssetFilter): GalleryRead<List<GalleryAsset>> {
        var readQos = "?"
        return run {
            readable {
                readQos = qosLabel()
                val options = predicateFor(
                    filter,
                )?.let { predicate -> PHFetchOptions().apply { this.predicate = predicate } }
                photoKitAssets(PHAsset.fetchAssetsWithOptions(options))
            }
        }.also {
            log.i {
                "gallery.assets(qos=${qosLabel()}) = ${"${(it as? GalleryRead.Read)?.value?.size ?: "not readable"}, read at qos=$readQos"}"
            }
        }
    }

    override suspend fun assetsById(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAsset>> =
        readable { if (ids.isEmpty()) emptyList() else photoKitAssets(fetchById(ids)) }

    override suspend fun resources(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAssetResources>> =
        run {
            readable { if (ids.isEmpty()) emptyList() else photoKitAssetResources(fetchById(ids)) }
        }.also {
            val read = (it as? GalleryRead.Read)?.value?.sumOf { a -> a.resources.size }
            log.i {
                "gallery.resources(${ids.size} asset(s), qos=${qosLabel()}) = ${read ?: "not readable"} resource(s)"
            }
        }

    /** User albums only: a smart album's title is system-localized, so nothing can be decided on it. */
    override suspend fun albums(): GalleryRead<List<GalleryAlbum>> = readable {
        records(
            PHAssetCollection.fetchAssetCollectionsWithType(
                PHAssetCollectionTypeAlbum,
                subtype = PHAssetCollectionSubtypeAlbumRegular,
                options = null,
            ),
        )
    }

    override suspend fun albumsById(ids: Set<PlatformAlbumId>): GalleryRead<List<GalleryAlbum>> = readable {
        if (ids.isEmpty()) {
            emptyList()
        } else {
            records(
                PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(ids.map { it.value }, null),
            )
        }
    }

    /**
     * One member fetch, bounded by [since] where PhotoKit can parse it. An unparseable bound drops the bound
     * rather than fetching nothing: under-returning would silently *admit* a denylisted photo, but fetching
     * nothing at all would make the whole rule a no-op.
     */
    override suspend fun albumMembers(
        album: PlatformAlbumId,
        createdFrom: Instant?,
    ): GalleryRead<Set<PlatformAssetId>> = readable {
        val collection = PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(listOf(album.value), null)
            .firstObject as? PHAssetCollection
        if (collection == null) {
            emptySet()
        } else {
            val options = createdFrom?.toNSDate()?.let { bound ->
                PHFetchOptions().apply {
                    predicate = NSPredicate.predicateWithFormat("creationDate >= %@", argumentArray = listOf(bound))
                }
            }
            val assets = PHAsset.fetchAssetsInAssetCollection(collection, options)
            buildSet { assets.forEachAsset { asset -> add(PlatformAssetId(asset.localIdentifier)) } }
        }
    }

    override val albumKind: LibraryAlbumKind = LibraryAlbumKind.COLLECTION

    override suspend fun createAlbum(title: String): PlatformAlbumId? {
        var placeholderId: String? = null
        return checkedObjC("createAlbum") { error ->
            PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait(
                changeBlock = {
                    objcBoundary(log, "createAlbum.changeBlock") {
                        val req = PHAssetCollectionChangeRequest.creationRequestForAssetCollectionWithTitle(title)
                        placeholderId = req.placeholderForCreatedAssetCollection.localIdentifier
                    }
                },
                error = error,
            )
        }.fold(
            onSuccess = { placeholderId?.let(::PlatformAlbumId) },
            onFailure = {
                log.w(it) { "createAlbum failed" }
                null
            },
        )
    }

    /**
     * `PHAssetResourceManager.writeDataForAssetResource` to [to], after removing whatever was there (a prior partial
     * export for the same key). It moved here from the app's `URLSession` uploader in phase 11f: reading a resource's
     * bytes is the photo library's, and the uploader now takes the file.
     */
    override suspend fun export(resource: GalleryResourceHandle, to: String): WriteOutcome {
        val handle = (resource as? PhotoKitResource)?.resource
            ?: return WriteOutcome.Failed("$resource is not a PhotoKit resource")
        val file = NSURL.fileURLWithPath(to)
        checkedObjC("removeItemAtURL") { NSFileManager.defaultManager.removeItemAtURL(file, error = it) }
        val error: NSError? = objcCallback(log, "export.completion") { done ->
            PHAssetResourceManager.defaultManager().writeDataForAssetResource(
                handle,
                toFile = file,
                options = null,
            ) { err ->
                objcBoundary(done) { err }
            }
        }
        return if (error == null) {
            WriteOutcome.Ok
        } else {
            WriteOutcome.Failed(
                "${handle.originalFilename}: ${ObjCFailure.of("writeDataForAssetResource", error).message}",
            )
        }
    }

    /**
     * Measured: adding an asset that is already in the collection is a no-op (simulator, iOS 26.5), which is what
     * lets a repeated gather re-add without placing anything twice. See
     * changes/archive/2026-09-21-album-gathers-retroactively.
     */
    override suspend fun addToAlbum(album: PlatformAlbumId, assets: Set<PlatformAssetId>): WriteOutcome {
        if (assets.isEmpty()) return WriteOutcome.Ok
        val collection = PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(listOf(album.value), null)
            .firstObject as? PHAssetCollection
            ?: return WriteOutcome.Failed("album $album no longer resolves")
        val found = fetchById(assets)
        if (found.count == 0uL) {
            log.i { "addToAlbum: none of ${assets.size} asset(s) resolve — nothing to add" }
            return WriteOutcome.Ok
        }
        return checkedObjC("addToAlbum") { error ->
            PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait(
                changeBlock = {
                    objcBoundary(log, "addToAlbum.changeBlock") {
                        // PHFetchResult conforms to NSFastEnumeration, so it is a valid addAssets argument.
                        PHAssetCollectionChangeRequest.changeRequestForAssetCollection(collection)?.addAssets(found)
                    }
                },
                error = error,
            )
        }.fold(onSuccess = { WriteOutcome.Ok }, onFailure = { WriteOutcome.Failed(it.message ?: "addToAlbum failed") })
    }

    /** The read, on [photoKitReadLane], or [GalleryRead.NotReadable] with no PhotoKit call when no grant allows one. */
    private suspend fun <T> readable(read: () -> T): GalleryRead<T> =
        if (access() !in READABLE) {
            GalleryRead.NotReadable
        } else {
            GalleryRead.Read(
                withContext(photoKitReadLane) { read() },
            )
        }

    private fun fetchById(ids: Set<PlatformAssetId>): PHFetchResult =
        PHAsset.fetchAssetsWithLocalIdentifiers(ids.map { it.value }, null)

    private fun records(collections: PHFetchResult): List<GalleryAlbum> = buildList {
        var i = 0uL
        while (i < collections.count) {
            val album = collections.objectAtIndex(i) as PHAssetCollection
            i++
            album.localizedTitle?.let { add(GalleryAlbum(PlatformAlbumId(album.localIdentifier), it)) }
        }
    }
}

/** Every asset of an already-fetched [result] — plain in-memory properties, no resource read. */
fun photoKitAssets(result: PHFetchResult): List<GalleryAsset> = buildList {
    result.forEachAsset { asset -> add(asset.toGalleryAsset()) }
}

/**
 * Every asset of an already-fetched [result] with its original resources — one `assetResourcesForAsset` round-trip
 * per asset. Two callers hold a fetch result already and must not issue another fetch to reach its assets: the
 * by-identifier read above, and the `LIMITED` selection observer, whose re-fetch would repeat a read already paid
 * for (the read discipline under a partial grant is about reading the right source — not about alert suppression).
 */
fun photoKitAssetResources(result: PHFetchResult): List<GalleryAssetResources> = buildList {
    result.forEachAsset { asset ->
        val resources = PHAssetResource.assetResourcesForAsset(asset).mapNotNull { any ->
            val resource = any as PHAssetResource
            // The kind, not the raw PHAssetResourceType: the ABI table is this module's (`photoKitResourceKind`).
            val kind = photoKitResourceKind(resource.type) ?: return@mapNotNull null
            GalleryResource(
                kind = kind,
                // Apple's UTI→MIME table stays iOS-only — and the resolved MIME is the ONLY content type reported,
                // so the UTI never reaches the wire. Measured at the origin (SE2, iOS 26.6): objects typed with the
                // raw UTI were stored as `public.jpeg`, which no HTTP client interprets.
                contentType = UTType.typeWithIdentifier(resource.uniformTypeIdentifier)?.preferredMIMEType
                    ?: "application/octet-stream",
                originalFilename = resource.originalFilename,
                handle = PhotoKitResource(resource),
            )
        }
        add(GalleryAssetResources(asset.toGalleryAsset(), resources))
    }
}

private inline fun PHFetchResult.forEachAsset(action: (PHAsset) -> Unit) {
    var i = 0uL
    while (i < count) {
        action(objectAtIndex(i) as PHAsset)
        i++
    }
}

/** The grants under which a library read answers anything — a full or a partial one. */
private val READABLE = setOf(LibraryGrant.GRANTED, LibraryGrant.LIMITED)
