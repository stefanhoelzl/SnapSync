package app.snapsync.model

/**
 * What the event album holds on this phone, and so how the app treats it. The gallery services read it from how the
 * platform holds an album, a fact fixed for the gallery adapter's life (decision record:
 * `changes/archive/2026-09-30-android-event-album` D1).
 */
enum class AlbumContents {
    /**
     * The photos the member shares and the ones they receive. An album is a collection an asset is added to, a photo may
     * be in any number of them, and adding moves nothing (iPhone).
     */
    SHARED_AND_RECEIVED,

    /**
     * The photos the member receives only. An album is the folder a file lives in, so filing a photo MOVES it, and only
     * a photo this app saved itself may be moved without asking: the member's own photos stay where the camera saved
     * them. A folder with no photo in it is no album (Android).
     */
    RECEIVED,
    ;

    /** Whether the member's own photos are placed in the album. */
    val placesOwnPhotos: Boolean get() = this == SHARED_AND_RECEIVED

    /**
     * Whether an album that holds no photo does not resolve. Then "does not resolve" means deleted only once the album
     * has held a photo, and the album map records when it has.
     */
    val absentWhileEmpty: Boolean get() = this == RECEIVED
}
