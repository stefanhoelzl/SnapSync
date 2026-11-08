package app.snapsync.data.album

import app.snapsync.data.gallery.PhotoGrants
import app.snapsync.data.gallery.platformId
import app.snapsync.domain.album.AlbumAdd
import app.snapsync.domain.album.AlbumContents
import app.snapsync.domain.album.AlbumPresence
import app.snapsync.domain.album.EventAlbumId
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventName
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.WriteOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * The event album's operations on the photo library that BOTH processes may hold: whether an album is still there, and
 * adding photos to one. Making an album is [AlbumCreation]'s, which only the app holds.
 */
class AlbumFiling(private val gallery: GalleryReader) {

    /** What the event album holds on this phone, read from how its library holds an album. */
    val contents: AlbumContents
        get() = when (gallery.albumKind) {
            LibraryAlbumKind.COLLECTION -> AlbumContents.SharedAndReceived
            LibraryAlbumKind.FOLDER -> AlbumContents.Received
        }

    /** Whether [album] still resolves in the library. */
    suspend fun presence(album: EventAlbumId): AlbumPresence =
        when (val read = gallery.albumsById(setOf(PlatformAlbumId(album.value)))) {
            is GalleryRead.Read -> when (read.value.isEmpty()) {
                true -> AlbumPresence.Missing
                false -> AlbumPresence.Resolves
            }
            GalleryRead.NotReadable -> AlbumPresence.Unreadable
        }

    /**
     * Adds [assets] to [album] — on a phone whose albums are folders, a MOVE. Best-effort, as the library is: a photo
     * it no longer holds is skipped, one already there is left as it is.
     */
    suspend fun add(album: EventAlbumId, assets: List<AssetId>): AlbumAdd =
        when (gallery.addToAlbum(PlatformAlbumId(album.value), assets.mapTo(linkedSetOf()) { it.platformId() })) {
            WriteOutcome.Ok -> AlbumAdd.Added
            is WriteOutcome.Failed -> AlbumAdd.NotAdded
        }
}

/**
 * Making an event's album: the app's alone, so the upload extension, which only places, can never make a second one.
 */
class AlbumCreation @AppProcessOnly constructor(private val gallery: GalleryReader) {

    /** A new album titled with the event's [name]. */
    suspend fun create(name: EventName): NewAlbum =
        gallery.createAlbum(name.text)?.let { NewAlbum.Created(EventAlbumId(it.value)) } ?: NewAlbum.NotCreated
}

/** What making an album produced. */
sealed interface NewAlbum {
    data class Created(val album: EventAlbumId) : NewAlbum

    /** The library made none — whatever the cause, nothing is filed into an album this time. */
    data object NotCreated : NewAlbum
}

/** The photo grant paired with the one before it, the first with none: the launch's look at the grant as it stood. */
class PhotoAccessChanges(private val grant: StateFlow<LibraryGrant>) {
    val changes: Flow<AccessChange> = flow {
        var before: PhotoAccess? = null
        grant.collect { granted ->
            val now = PhotoGrants.of(granted).access
            emit(AccessChange(before, now))
            before = now
        }
    }
}

/** A photo-grant emission: [now], and what it was at the emission before — `null` for the first. */
class AccessChange(val before: PhotoAccess?, val now: PhotoAccess)
