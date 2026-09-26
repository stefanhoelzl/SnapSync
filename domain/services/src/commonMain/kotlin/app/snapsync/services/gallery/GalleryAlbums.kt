package app.snapsync.services.gallery

import app.snapsync.model.AssetId
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.GalleryRead
import app.snapsync.model.SelectionCalibration
import app.snapsync.model.WriteOutcome
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
) {

    /**
     * Create a new album titled [name] and return its id, or `null` if creation failed. **Only the app**
     * calls this (it is the sole creator; see [AlbumCoordinator]).
     *
     * Absence: null means creation failed, whatever the cause, and the membership then files nothing
     * into an album the member explicitly opted into — silently. That is the ONE verdict in this
     * inventory recorded as unsatisfying rather than safe: the causes share a consequence, so the
     * collapse is legal, but whether the consequence itself is acceptable under an explicit
     * `saveToAlbum` opt-in is an open product question (decision record:
     * `changes/archive/…-absence-is-never-silent`, Open Questions).
     */
    suspend fun ensureCreated(name: String): String? = gallery.createAlbum(name)

    /** An album the gallery cannot read does not resolve — the caller then creates, which fails the same way. */
    suspend fun exists(albumLocalId: String): Boolean =
        (gallery.albumsById(setOf(albumLocalId)) as? GalleryRead.Read)?.value?.isNotEmpty() == true

    /**
     * Add the library assets [assetIds] (the gallery's asset ids, as the ledger and the download store carry
     * them) to the album [albumLocalId]. Best-effort: a missing asset is skipped, adding an already-present
     * asset is a no-op.
     */
    suspend fun add(albumLocalId: String, assetIds: List<AssetId>) {
        if (assetIds.isEmpty()) return
        val outcome = gallery.addToAlbum(albumLocalId, assetIds.toSet())
        if (outcome != WriteOutcome.Ok) log.w { "add to album $albumLocalId: $outcome (${assetIds.size} asset(s))" }
    }

    /**
     * Cost is O(albums), never O(assets): one listing, then one member read per denied album. An unreadable
     * gallery answers the empty set — the denylist is a subtraction and the policy admits on doubt.
     */
    suspend fun assetIdsInAlbums(calibration: SelectionCalibration, since: CaptureCutoff): Set<AssetId> {
        val albums = (gallery.albums() as? GalleryRead.Read)?.value ?: return emptySet()
        return albums.filter { calibration.isDenylistedAlbum(it.title) }.flatMapTo(mutableSetOf()) { album ->
            val members = (gallery.albumMembers(album.id, since) as? GalleryRead.Read)?.value.orEmpty()
            log.i { "denylisted album '${album.title}': ${members.size} member(s) in scope" }
            members
        }
    }
}
