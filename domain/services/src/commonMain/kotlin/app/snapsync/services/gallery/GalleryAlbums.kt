package app.snapsync.services.gallery

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.model.AlbumKind
import app.snapsync.model.SelectionCalibration
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.PlatformAlbumId
import co.touchlab.kermit.Logger

/**
 * The event album's and the denylist's album operations over the [GalleryReader]. The gallery only lists and
 * fills albums; which titles are denied is decided here, by the [SelectionCalibration] the caller passes, and matched
 * per [SelectionCalibration.isDenylistedAlbum].
 */
class GalleryAlbums(
    private val gallery: GalleryReader,
    private val log: Logger = Logger.withTag("albums"),
) {

    /** How the member's library holds an album, as the app reads the platform's answer. */
    val kind: AlbumKind get() = gallery.albumKind.albumKind()

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
    suspend fun ensureCreated(name: String): String? {
        val created = gallery.createAlbum(name) ?: return null
        return created.value
    }

    /** An album the gallery cannot read does not resolve — the caller then creates, which fails the same way. */
    suspend fun exists(albumLocalId: String): Boolean =
        when (val read = gallery.albumsById(setOf(PlatformAlbumId(albumLocalId)))) {
            is GalleryRead.Read -> read.value.isNotEmpty()
            GalleryRead.NotReadable -> false
        }

    /**
     * Add the library assets [assetIds] (the gallery's asset ids, as the ledger and the download store carry
     * them) to the album [albumLocalId]. Best-effort: a missing asset is skipped, adding an already-present
     * asset is a no-op. Answers whether the library took the add.
     */
    suspend fun add(albumLocalId: String, assetIds: List<AssetId>): Boolean {
        if (assetIds.isEmpty()) return false
        val outcome = gallery.addToAlbum(
            PlatformAlbumId(albumLocalId),
            assetIds.mapTo(linkedSetOf()) { it.platformId() },
        )
        if (outcome != WriteOutcome.Ok) log.w { "add to album $albumLocalId: $outcome (${assetIds.size} asset(s))" }
        return outcome == WriteOutcome.Ok
    }

    /**
     * Cost is O(albums), never O(assets): one listing, then one member read per denied album. An unreadable
     * gallery answers the empty set — the denylist is a subtraction and the policy admits on doubt.
     */
    suspend fun assetIdsInAlbums(calibration: SelectionCalibration, since: CaptureCutoff): Set<AssetId> {
        val albums = when (val read = gallery.albums()) {
            is GalleryRead.Read -> read.value
            GalleryRead.NotReadable -> return emptySet()
        }
        return albums.filter { calibration.isDenylistedAlbum(it.title) }.flatMapTo(mutableSetOf()) { album ->
            val members = (gallery.albumMembers(album.id, since.at.at) as? GalleryRead.Read)?.value.orEmpty()
            log.i { "denylisted album '${album.title}': ${members.size} member(s) in scope" }
            members.map { it.assetId() }
        }
    }
}

/** What the platform's way of holding an album means to the app: the same kind. */
fun LibraryAlbumKind.albumKind(): AlbumKind = when (this) {
    LibraryAlbumKind.COLLECTION -> AlbumKind.COLLECTION
    LibraryAlbumKind.FOLDER -> AlbumKind.FOLDER
}
