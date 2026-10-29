package app.snapsync.feature.support

import app.snapsync.data.gallery.assetId
import app.snapsync.data.sharing.Contribution
import app.snapsync.data.sharing.DenylistedAlbums
import app.snapsync.data.sharing.GalleryWalk
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.data.time.WallClock
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.time.Timestamp
import app.snapsync.mock.LibraryAssets
import app.snapsync.mock.PhotoLibraryMock
import app.snapsync.model.Resource
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.Clock
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import app.snapsync.ports.SelectionSnapshot
import app.snapsync.services.gallery.ContributionKeyedResources
import app.snapsync.services.gallery.KeyedRead
import app.snapsync.services.gallery.KeyedResources
import app.snapsync.services.gallery.resourcesFrom
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

// A member's own photos as the feature tests read them: the REAL `OwnPhotos` (the data layer's one place a
// contribution is derived, read and admitted) over the in-memory photo library, observed at the gallery port.

/** The capture range `[from, until]`, its event the same dates — `until` 30 days on unless stated. */
fun captureRange(from: String, until: String? = null): CaptureRange {
    val start = Timestamp.of(Instant.parse(from))
    val end = Timestamp.of(until?.let(Instant::parse) ?: (Instant.parse(from) + 30.days))
    val verdict = CaptureRange.of(CaptureCutoff(start), CaptureCeiling(end), EventStart(start), EventEnd(end))
    return (verdict as CaptureRangeVerdict.Ok).range
}

/**
 * A library photo whose platform id is [id], captured at [date] (a text that does not parse is undated), with one
 * primary resource named `IMG.<ext>` — so its upload key is `<id>-primary.<ext>` ([keyOf]). The other facts are an
 * ordinary 12 MP camera photo's unless stated.
 */
fun photo(
    id: String,
    date: String = LibraryAssets.DEFAULT_DATE,
    ext: String = "heic",
    isScreenshot: Boolean = false,
    isScreenRecording: Boolean = false,
    isVideo: Boolean = false,
    width: Long = 4032,
    height: Long = 3024,
    isEdited: Boolean = false,
    live: Boolean = false,
): GalleryAssetResources = LibraryAssets.photo(
    id,
    creationDate = date,
    resources = listOfNotNull(
        LibraryAssets.primaryResource(filename = "IMG.$ext", contentType = "image/$ext"),
        LibraryAssets.Resource(ResourceKind.PAIRED_VIDEO, "video/quicktime", "IMG.mov").takeIf { live },
    ),
    isScreenshot = isScreenshot,
    isScreenRecording = isScreenRecording,
    isVideo = isVideo,
    pixelWidth = width,
    pixelHeight = height,
    isEdited = isEdited,
)

/** The upload key of the photo [id]'s primary resource, as [photo] names it. */
fun keyOf(id: String, ext: String = "heic"): String = "$id-primary.$ext"

/** Every uploadable resource of [assets], exactly as the cycle reads them — what a ledger row is recorded from. */
fun resourcesOf(vararg assets: GalleryAssetResources): List<Resource> = resourcesFrom(assets.toList())

/** The one primary resource of [asset]. */
fun resourceOf(asset: GalleryAssetResources): Resource = resourcesOf(asset).first()

/**
 * The gallery port, observed: every walk's filter and every asset whose resources were read. [unreadable] makes a walk
 * answer that the library could not be read; [widened] makes it ignore the filter — a platform over-returning.
 */
class ObservedGallery(private val inner: GalleryReader) : GalleryReader by inner {
    val walks = mutableListOf<AssetFilter>()
    val read = mutableListOf<AssetId>()
    var unreadable = false
    var widened = false

    /** What happens as a walk reads the library — where a test's time runs out. */
    var onWalk: () -> Unit = {}

    override suspend fun assets(filter: AssetFilter): GalleryRead<List<GalleryAsset>> {
        walks += filter
        onWalk()
        if (unreadable) return GalleryRead.NotReadable
        return inner.assets(if (widened) AssetFilter(null, null) else filter)
    }

    override suspend fun resources(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAssetResources>> {
        read += ids.map { it.assetId() }
        return inner.resources(ids)
    }
}

/** The keys a cycle resolved, counted with repeats; [before] runs ahead of each resolve. */
class ObservedKeys(private val inner: KeyedResources) : KeyedResources {
    val keys = mutableSetOf<String>()
    var calls = 0
    var before: suspend (Set<String>) -> Unit = {}

    override suspend fun resourcesFor(keys: Set<String>): KeyedRead {
        this.keys += keys
        calls++
        before(keys)
        return inner.resourcesFor(keys)
    }
}

/**
 * A member's library under [grant], and the real [OwnPhotos] over it. [gallery] observes the discovery's reads; the
 * key resolve ([keys]) reads through a second, unobserved face of the same library and the same selection, so a
 * resolve is never mistaken for a discovery read.
 */
class OwnLibrary(grant: LibraryGrant = LibraryGrant.GRANTED, clock: Clock = testClock()) {
    val mock = PhotoLibraryMock(access = grant)
    val gallery = ObservedGallery(mock.port())
    val selection = SelectionSnapshots()
    private val wallClock = WallClock(clock)
    val photos = ownPhotos(gallery)
    val keys = ObservedKeys(ContributionKeyedResources(ownPhotos(mock.port())))

    private fun ownPhotos(port: GalleryReader) =
        OwnPhotos(port, GalleryWalk(port, wallClock), selection, DenylistedAlbums(port), wallClock)

    fun add(vararg assets: GalleryAssetResources) = assets.forEach(mock::add)

    /** The member's partial selection becomes [assets], read as the selection observer reads it. */
    suspend fun select(assets: List<GalleryAssetResources>) {
        selection.published(flowOf(SelectionSnapshot(assets))) {}.collect()
    }

    /** [id] is filed in another app's album titled [title]. */
    suspend fun inAlbum(title: String, id: String) = mock.operator.placeIn(title, id)

    /** What a membership of [direction] sharing [range] contributes, [received] its echo set. */
    suspend fun contribution(
        range: CaptureRange,
        received: Set<AssetId> = emptySet(),
        direction: Direction = Direction.Both,
    ): Contribution = photos.contribution(direction, range, received)
}
