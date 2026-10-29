package app.snapsync.data.sharing

import app.snapsync.data.gallery.assetId
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.SelectionCalibration
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader

/**
 * The photos in an album a messaging or social app made ([SelectionCalibration.denies]) — the album denylist's ids.
 *
 * Cost is O(albums), never O(assets): one listing, then one member read per denied album. Asked only when the library
 * is walked (`OwnPhotos`), never under a partial grant or no grant: there the album structure is unreadable, and on an
 * undecided grant an album fetch raises the system's permission dialog. An unreadable gallery answers none — the
 * denylist is a subtraction, and the policy admits on doubt.
 */
class DenylistedAlbums(private val gallery: GalleryReader) {
    private val log = Log("DenylistedAlbums")

    /** The photos captured since [from] in a denied album. */
    suspend fun members(from: CaptureCutoff): Set<AssetId> = when (val read = gallery.albums()) {
        GalleryRead.NotReadable -> emptySet()
        is GalleryRead.Read ->
            read.value
                .filter { SelectionCalibration.STANDARD.denies(it.title) }
                .flatMapTo(mutableSetOf()) { members(it, from) }
    }

    private suspend fun members(album: GalleryAlbum, from: CaptureCutoff): Set<AssetId> {
        val members = when (val read = gallery.albumMembers(album.id, from.at.at)) {
            GalleryRead.NotReadable -> emptySet()
            is GalleryRead.Read -> read.value.mapTo(mutableSetOf()) { it.assetId() }
        }
        log.i("denylisted album", "title" of album.title, "members" of members.size)
        return members
    }
}
