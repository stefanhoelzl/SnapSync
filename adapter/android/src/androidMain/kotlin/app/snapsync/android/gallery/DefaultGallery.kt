package app.snapsync.android.gallery

/**
 * The member's **default gallery** on Android (capability `photo-sharing`): the `DCIM` folder and every folder under
 * it, on every storage volume — where camera apps save. Every read of the gallery is scoped to it, and the selection
 * policy is the one decision over what it returns (`docs/architecture.md`).
 *
 * It has two scopes (`changes/archive/2026-09-30-android-event-album` D8), because the event albums live inside it,
 * in [ALBUM_ROOT]:
 * - **the library** ([SQL]): all of `DCIM`. The by-id reads and the album reads use it, so a received photo
 *   saved into an event album still reads as present, and the album as holding it.
 * - **the candidates** ([CANDIDATE_SQL]): `DCIM` without [ALBUM_ROOT]. The reads that decide what the
 *   member shares use it — the policy read, the selection snapshot, the album listing the denylist matches — so nothing
 *   in an event album is ever shared as the member's own, even once a reinstall has forgotten it was received.
 *
 * A path is MediaStore's `RELATIVE_PATH` — volume-relative, so an SD card's `DCIM` counts as the phone's own does.
 * Matched ignoring case, as SQLite's `LIKE` does for ASCII, so [isAlbumFolder] agrees with the fragments on every path.
 */
internal object DefaultGallery {

    private const val PREFIX = "DCIM/"

    /** The folder the event albums are created in, one folder each (capability `event-album`). */
    const val ALBUM_ROOT: String = "DCIM/SnapSync/"

    /** The selection fragment a MediaStore query narrows to the whole default gallery with. */
    const val SQL: String = "relative_path LIKE '$PREFIX%'"

    /** The selection fragment a MediaStore query narrows to the photos the member may share with. */
    const val CANDIDATE_SQL: String = "$SQL AND relative_path NOT LIKE '$ALBUM_ROOT%'"

    /** Whether [relativePath] lies in [ALBUM_ROOT] — an event album's folder, or the root itself. */
    fun isAlbumFolder(relativePath: String?): Boolean = relativePath != null && relativePath.startsWith(ALBUM_ROOT, ignoreCase = true)
}
