package app.snapsync.ports

import kotlin.jvm.JvmInline

/**
 * What a gallery read produced: the answer, or the statement that the gallery cannot be read at all right now
 * (`docs/architecture.md`, "Absence is never silent").
 *
 * [NotReadable] is the platform's own fact — no grant that lets this process see the library — and never
 * "nothing matched": a read that found nothing is `Read(empty)`, a counted zero. What a read under a
 * **partial** grant means (the selection, not the library) is for the caller to judge; the gallery answers
 * with what the platform shows.
 */
sealed interface GalleryRead<out T> {
    data class Read<out T>(val value: T) : GalleryRead<T>

    data object NotReadable : GalleryRead<Nothing>
}

/** An album's id as the platform itself writes it — PhotoKit's `localIdentifier`, the folder's path on Android. */
@JvmInline
value class PlatformAlbumId(val value: String)

/** One user-created album: an album the member or another app made (never a system-made smart album). */
data class GalleryAlbum(val id: PlatformAlbumId, val title: String)

/** How the platform's library holds an album — fixed for the adapter's life. */
enum class LibraryAlbumKind {
    /** A collection an asset is added to; a photo may be in any number of them (PhotoKit). */
    COLLECTION,

    /** The folder a file lives in; filing a photo moves it, and a photo is in one folder only (MediaStore). */
    FOLDER,
}
