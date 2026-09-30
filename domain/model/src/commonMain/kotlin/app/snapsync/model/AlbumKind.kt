package app.snapsync.model

/**
 * How the member's photo library holds an album (capability `event-album`) — a fact of the platform, fixed for the
 * gallery adapter's life, which decides what the event album may hold.
 */
enum class AlbumKind {
    /**
     * An album is a collection an asset is added to, and a photo may be in any number of them (iPhone). Adding moves
     * nothing, so the album holds the member's own photos and the ones they receive.
     */
    COLLECTION,

    /**
     * An album is the folder a file lives in, and a file lives in exactly one (Android). Filing a photo MOVES it, and only
     * a photo this app saved itself may be moved without asking, so the album holds the photos the member receives; their
     * own photos stay where the camera saved them (decision record: `changes/android-event-album` D1).
     */
    FOLDER,
}
