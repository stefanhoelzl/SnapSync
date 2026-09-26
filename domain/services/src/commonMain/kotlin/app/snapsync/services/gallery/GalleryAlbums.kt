package app.snapsync.services.gallery

import app.snapsync.model.AssetId
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.GalleryRead
import app.snapsync.model.SelectionCalibration
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.AlbumManager
import app.snapsync.ports.GalleryReader
import co.touchlab.kermit.Logger

/**
 * The event album's and the denylist's album operations over the [GalleryReader] (capabilities `event-album`,
 * `photo-sharing`). The gallery only lists and fills albums; which titles are denied is decided here, by the
 * [SelectionCalibration] the caller passes, and matched per [SelectionCalibration.isDenylistedAlbum].
 */
class GalleryAlbums(
    private val gallery: GalleryReader,
    private val log: Logger = Logger.withTag("albums"),
) : AlbumManager {

    override suspend fun ensureCreated(name: String): String? = gallery.createAlbum(name)

    /** An album the gallery cannot read does not resolve — the caller then creates, which fails the same way. */
    override suspend fun exists(albumLocalId: String): Boolean =
        (gallery.albumsById(setOf(albumLocalId)) as? GalleryRead.Read)?.value?.isNotEmpty() == true

    override suspend fun add(albumLocalId: String, assetIds: List<AssetId>) {
        if (assetIds.isEmpty()) return
        val outcome = gallery.addToAlbum(albumLocalId, assetIds.toSet())
        if (outcome != WriteOutcome.Ok) log.w { "add to album $albumLocalId: $outcome (${assetIds.size} asset(s))" }
    }

    /**
     * Cost is O(albums), never O(assets): one listing, then one member read per denied album. An unreadable
     * gallery answers the empty set — the denylist is a subtraction and the policy admits on doubt.
     */
    override suspend fun assetIdsInAlbums(calibration: SelectionCalibration, since: CaptureCutoff): Set<AssetId> {
        val albums = (gallery.albums() as? GalleryRead.Read)?.value ?: return emptySet()
        return albums.filter { calibration.isDenylistedAlbum(it.title) }.flatMapTo(mutableSetOf()) { album ->
            val members = (gallery.albumMembers(album.id, since) as? GalleryRead.Read)?.value.orEmpty()
            log.i { "denylisted album '${album.title}': ${members.size} member(s) in scope" }
            members
        }
    }
}
