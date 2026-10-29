package app.snapsync.data.sharing

import app.snapsync.data.membership.share
import app.snapsync.data.time.WallClock
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.WalkBounds
import app.snapsync.domain.time.Timestamp
import app.snapsync.mock.ClockMock
import app.snapsync.mock.inMemoryGallery
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.SelectionSnapshot
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Instant

/** The fixture event's dates are 2026-07-14T18:00Z..2026-07-21T18:00Z (`share()`); now lies inside them. */
internal const val NOW = "2026-07-16T00:00:00Z"

/** A capture inside the fixture event. */
internal const val DURING = "2026-07-15T10:00:00Z"

/** A capture before the fixture event's start. */
internal const val BEFORE = "2026-07-12T10:00:00Z"

internal fun at(text: String): Timestamp = Timestamp.of(Instant.parse(text))

/** The fixture event's own dates, or [from]..[until] of it. */
internal fun range(from: String = "2026-07-14T18:00:00Z", until: String = "2026-07-21T18:00:00Z"): CaptureRange =
    share(from = from, until = until).range

/** A walk narrowed to the [range] of [from]..[until]. */
internal fun within(from: String = "2026-07-14T18:00:00Z", until: String = "2026-07-21T18:00:00Z"): WalkBounds =
    WalkBounds.Within(range(from, until))

/**
 * A member's photo library behind the gallery mock, and the reader the data layer is handed: it records every read the
 * layer asks of the platform, and can make one kind of read unreadable while the grant stays readable.
 */
internal class Library(
    vararg photos: GalleryAssetResources,
    grant: LibraryGrant = LibraryGrant.GRANTED,
    albums: Map<String, Set<String>> = emptyMap(),
) {
    val photos = MutableStateFlow(photos.toList())
    val grant = MutableStateFlow(grant)
    val albums = MutableStateFlow(albums.mapValues { (_, ids) -> ids.mapTo(mutableSetOf(), ::PlatformAssetId) })
    val gallery: Gallery = inMemoryGallery(this.photos, this.grant, this.albums)

    val clock = ClockMock(Instant.parse(NOW))
    val wallClock = WallClock(clock.port())

    /** Every read asked, in order — `assets(from..until)`, `resources([ids])`, `albums`, `albumMembers(title from)`. */
    val calls = mutableListOf<String>()
    var assetsReadable = true

    /** The titles whose members cannot be read. */
    var unreadableMembers = emptySet<String>()

    val reader: GalleryReader = object : GalleryReader by gallery {
        override suspend fun assets(filter: AssetFilter): GalleryRead<List<GalleryAsset>> {
            calls += "assets(${filter.createdFrom}..${filter.createdUntil})"
            return if (assetsReadable) gallery.assets(filter) else GalleryRead.NotReadable
        }

        override suspend fun resources(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAssetResources>> {
            calls += "resources(${ids.map { it.value }})"
            return gallery.resources(ids)
        }

        override suspend fun albums(): GalleryRead<List<GalleryAlbum>> {
            calls += "albums"
            return gallery.albums()
        }

        override suspend fun albumMembers(album: PlatformAlbumId, createdFrom: Instant?): GalleryRead<Set<PlatformAssetId>> {
            val title = album.value.removePrefix("user-album:")
            calls += "albumMembers($title from $createdFrom)"
            return if (title in unreadableMembers) GalleryRead.NotReadable else gallery.albumMembers(album, createdFrom)
        }
    }

    val selection = SelectionSnapshots()
    var walks = 0
    private val walk = GalleryWalk(reader, wallClock)

    /** The full walk, counted. */
    val counted = object : LibraryWalk {
        override suspend fun read(bounds: WalkBounds): WalkRead {
            walks++
            return walk.read(bounds)
        }
    }

    val own = OwnPhotos(reader, counted, selection, DenylistedAlbums(reader), wallClock)

    /** The member picks [photos] — the selection the observer reads under a partial grant. */
    suspend fun select(vararg photos: GalleryAssetResources) {
        selection.published(flowOf(SelectionSnapshot(photos.toList())), beforePublish = {}).collect()
    }

    /** The reads that were of the library's albums. */
    fun albumReads(): List<String> = calls.filter { it.startsWith("album") }

    suspend fun contribution(direction: Direction = Direction.Both, range: CaptureRange = range()): Contribution =
        own.contribution(direction, range, received = emptySet())
}

internal fun ids(vararg text: String): List<AssetId> = text.map(::testAssetId)

/** The ids of what a discovery admitted. */
internal fun DiscoveryRead.Discovered.admittedIds(): List<AssetId> = discovery.admitted.map { it.facts.assetId }

/** The ids of what a gallery read resolved, or `null` when it could not be read. */
internal fun GalleryRead<List<GalleryAssetResources>>.resolvedIds(): List<String>? = when (this) {
    is GalleryRead.Read -> value.map { it.asset.id.value }
    GalleryRead.NotReadable -> null
}
