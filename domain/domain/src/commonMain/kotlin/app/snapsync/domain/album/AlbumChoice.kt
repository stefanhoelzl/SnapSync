package app.snapsync.domain.album

/** The member's choice for the event album: on a join, in the event's settings, and on the membership that holds it. */
enum class AlbumChoice {
    /** The event's photos go into an album named after the event. */
    SaveToAlbum,

    /** No album: the photos stay where the library puts them. */
    LibraryOnly,
}
