@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.gallery

import app.snapsync.ios.qos.photoKitReadLane
import app.snapsync.ios.qos.qosLabel
import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumRecord
import app.snapsync.model.AssetFacts
import app.snapsync.model.AssetId
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.GalleryAccess
import app.snapsync.model.GalleryRead
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.WriteOutcome
import app.snapsync.model.denormalizeAssetId
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.logging.invocation
import app.snapsync.model.normalizeAssetId
import app.snapsync.objc.checkedObjC
import app.snapsync.objc.objcBoundary
import app.snapsync.ports.GalleryReader
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.withContext
import platform.Foundation.NSPredicate
import platform.Photos.PHAsset
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionChangeRequest
import platform.Photos.PHAssetCollectionSubtypeAlbumRegular
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHAssetResource
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult
import platform.Photos.PHPhotoLibrary
import platform.UniformTypeIdentifiers.UTType

/**
 * The PhotoKit [GalleryReader] — every photo-library read and album write either process makes, and the only
 * place outside the app's own adapters that touches `PHAsset`, `PHAssetResource` or `PHAssetCollection`.
 *
 * **Asset ids cross normalized** (`/`→`_`, the form the ledger and the upload keys carry): every id handed out is
 * normalized and every id handed in is converted back, so the core holds one form. The conversion is exact in
 * both directions — a `localIdentifier` never contains `_`. Album ids cross as PhotoKit's own.
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

    override fun access(): GalleryAccess = currentPhotoPermission()

    /**
     * Narrowed by [predicateFor] — whatever of the policy PhotoKit can express; the rest falls to the caller's
     * admission. Both QoS classes on the one line (capability `privacy-security`): the caller's, and the lane's
     * the PhotoKit calls were issued at.
     */
    override suspend fun assets(policy: SelectionPolicy): GalleryRead<List<AssetFacts>> {
        var readQos = "?"
        return log.invocation(
            "gallery.assets",
            params = "qos=${qosLabel()}",
            result = { "${(it as? GalleryRead.Read)?.value?.size ?: "not readable"}, read at qos=$readQos" },
        ) {
            readable {
                readQos = qosLabel()
                val options = predicateFor(policy)?.let { predicate -> PHFetchOptions().apply { this.predicate = predicate } }
                photoKitFacts(PHAsset.fetchAssetsWithOptions(options))
            }
        }
    }

    override suspend fun assetsById(ids: Set<AssetId>): GalleryRead<List<AssetFacts>> =
        readable { if (ids.isEmpty()) emptyList() else photoKitFacts(fetchById(ids)) }

    override suspend fun resources(ids: Set<AssetId>): GalleryRead<List<RawAsset>> =
        log.invocation(
            "gallery.resources",
            params = "${ids.size} asset(s), qos=${qosLabel()}",
            result = { "${(it as? GalleryRead.Read)?.value?.sumOf { a -> a.rawResources.size } ?: "not readable"} resource(s)" },
        ) {
            readable { if (ids.isEmpty()) emptyList() else photoKitRawAssets(fetchById(ids)) }
        }

    /** User albums only: a smart album's title is system-localized, so nothing can be decided on it. */
    override suspend fun albums(): GalleryRead<List<AlbumRecord>> = readable {
        records(
            PHAssetCollection.fetchAssetCollectionsWithType(
                PHAssetCollectionTypeAlbum,
                subtype = PHAssetCollectionSubtypeAlbumRegular,
                options = null,
            ),
        )
    }

    override suspend fun albumsById(ids: Set<AlbumId>): GalleryRead<List<AlbumRecord>> = readable {
        if (ids.isEmpty()) emptyList() else records(PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(ids.toList(), null))
    }

    /**
     * One member fetch, bounded by [since] where PhotoKit can parse it. An unparseable bound drops the bound
     * rather than fetching nothing: under-returning would silently *admit* a denylisted photo, but fetching
     * nothing at all would make the whole rule a no-op.
     */
    override suspend fun albumMembers(album: AlbumId, since: CaptureCutoff?): GalleryRead<Set<AssetId>> = readable {
        val collection = PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(listOf(album), null)
            .firstObject as? PHAssetCollection
        if (collection == null) {
            emptySet()
        } else {
            val options = since?.let { Iso8601.parseTolerant(it.at.iso) }?.let { bound ->
                PHFetchOptions().apply {
                    predicate = NSPredicate.predicateWithFormat("creationDate >= %@", argumentArray = listOf(bound))
                }
            }
            val assets = PHAsset.fetchAssetsInAssetCollection(collection, options)
            buildSet { assets.forEachAsset { add(normalizeAssetId(it.localIdentifier)) } }
        }
    }

    override suspend fun createAlbum(title: String): AlbumId? {
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
            onSuccess = { placeholderId },
            onFailure = { log.w(it) { "createAlbum failed" }; null },
        )
    }

    /**
     * Measured: adding an asset that is already in the collection is a no-op (simulator, iOS 26.5), which is what
     * lets a repeated gather re-add without placing anything twice. See
     * changes/archive/2026-09-21-album-gathers-retroactively.
     */
    override suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>): WriteOutcome {
        if (assets.isEmpty()) return WriteOutcome.Ok
        val collection = PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(listOf(album), null)
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
        if (!access().grantsPhotoAccess) GalleryRead.NotReadable else GalleryRead.Read(withContext(photoKitReadLane) { read() })

    private fun fetchById(ids: Set<AssetId>): PHFetchResult =
        PHAsset.fetchAssetsWithLocalIdentifiers(ids.map(::denormalizeAssetId), null)

    private fun records(collections: PHFetchResult): List<AlbumRecord> = buildList {
        var i = 0uL
        while (i < collections.count) {
            val album = collections.objectAtIndex(i) as PHAssetCollection
            i++
            album.localizedTitle?.let { add(AlbumRecord(album.localIdentifier, it)) }
        }
    }
}

/** The facts of every asset of an already-fetched [result] — plain in-memory properties, no resource read. */
fun photoKitFacts(result: PHFetchResult): List<AssetFacts> = buildList {
    result.forEachAsset { add(it.toAssetFacts(creationDateOf(it))) }
}

/**
 * Every asset of an already-fetched [result] with all of its resources — one `assetResourcesForAsset` round-trip
 * per asset. Two callers hold a fetch result already and must not issue another fetch to reach its assets: the
 * by-identifier read above, and the `LIMITED` selection observer, whose re-fetch would repeat a read already paid
 * for (capability `photo-access`, whose read discipline is about reading the right source under a partial grant —
 * not about alert suppression).
 */
fun photoKitRawAssets(result: PHFetchResult): List<RawAsset> = buildList {
    result.forEachAsset { asset ->
        val creationDate = creationDateOf(asset)
        val rawResources = PHAssetResource.assetResourcesForAsset(asset).map { any ->
            val resource = any as PHAssetResource
            RawResource(
                // The role, not the raw PHAssetResourceType: the ABI table is this module's
                // (`photoKitResourceRole`), so no platform value crosses the port.
                role = photoKitResourceRole(resource.type),
                // Apple's UTI→MIME table stays iOS-only — and the resolved MIME is the ONLY content type reported,
                // so the UTI never reaches the wire. Measured at the origin (SE2, iOS 26.6): objects typed with the
                // raw UTI were stored as `public.jpeg`, which no HTTP client interprets.
                mimeContentType = UTType.typeWithIdentifier(resource.uniformTypeIdentifier)?.preferredMIMEType
                    ?: "application/octet-stream",
                originalFilename = resource.originalFilename,
                handle = resource, // opaque PHAssetResource, crosses uninterpreted
            )
        }
        add(RawAsset(normalizeAssetId(asset.localIdentifier), creationDate, rawResources, asset.toAssetFacts(creationDate)))
    }
}

// Per-asset capture timestamp (ISO-8601), through the ONE shared formatter: building one per asset was most of a
// walk's CPU (see [Iso8601]).
private fun creationDateOf(asset: PHAsset): String = asset.creationDate?.let { Iso8601.format(it) } ?: ""

private inline fun PHFetchResult.forEachAsset(action: (PHAsset) -> Unit) {
    var i = 0uL
    while (i < count) {
        action(objectAtIndex(i) as PHAsset)
        i++
    }
}
