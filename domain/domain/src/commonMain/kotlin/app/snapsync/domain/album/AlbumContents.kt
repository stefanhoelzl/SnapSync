package app.snapsync.domain.album

import kotlin.jvm.JvmInline

/** An event album's id as the domain carries it: what the platform wrote for it, read back unchanged. */
@JvmInline
value class EventAlbumId(val value: String)

/** Whether an event's album has held a photo — the album map's mark, reset when a new album is remembered. */
enum class AlbumFill { NotYetFilled, Filled }

/** What the photo library answered for a stored album's id. */
enum class AlbumPresence {
    Resolves,
    Missing,

    /** The library could not be read: the album is treated as gone, as it always was — a creation then fails too. */
    Unreadable,
}

/** What a stored album is, once the phone's way of holding an album has read [AlbumPresence] and [AlbumFill]. */
enum class AlbumStanding { Present, Deleted }

/** Where the member's own photos go on this phone. */
enum class OwnPhotoHome { IntoAlbum, StayInCameraFolder }

/**
 * What the event album holds on this phone, and so how the app treats it: a platform fact, fixed for the photo
 * library's life (decision record `2026-09-30-android-event-album` D1).
 */
enum class AlbumContents {
    /**
     * The photos the member shares and the ones they receive. An album is a collection an asset is added to, a photo
     * may be in any number of them, and adding moves nothing (iPhone).
     */
    SharedAndReceived,

    /**
     * The photos the member receives only. An album is the folder a file lives in, so filing a photo MOVES it, and only
     * a photo this app saved itself may be moved without asking: the member's own photos stay where the camera saved
     * them. A folder with no photo in it is no album (Android).
     */
    Received,
    ;

    /** Where the member's own photos go: never moved out of the camera folder on a phone whose albums are folders. */
    val ownPhotos: OwnPhotoHome
        get() = when (this) {
            SharedAndReceived -> OwnPhotoHome.IntoAlbum
            Received -> OwnPhotoHome.StayInCameraFolder
        }

    /**
     * What a stored album is here. A folder that never held a photo does not resolve either, so it is no album yet
     * rather than a deleted one; once it has held a photo, not resolving means the member deleted, emptied or renamed
     * it.
     */
    fun standing(fill: AlbumFill, presence: AlbumPresence): AlbumStanding = when (this) {
        SharedAndReceived -> presence.standing()
        Received -> when (fill) {
            AlbumFill.NotYetFilled -> AlbumStanding.Present
            AlbumFill.Filled -> presence.standing()
        }
    }

    private fun AlbumPresence.standing(): AlbumStanding = when (this) {
        AlbumPresence.Resolves -> AlbumStanding.Present
        AlbumPresence.Missing, AlbumPresence.Unreadable -> AlbumStanding.Deleted
    }
}
