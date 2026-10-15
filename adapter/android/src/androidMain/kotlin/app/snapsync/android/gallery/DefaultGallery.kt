package app.snapsync.android.gallery

/**
 * The member's **default gallery** on Android under a full grant: the `DCIM` folder and every folder under
 * it, on every storage volume — where camera apps save. Every read of the gallery is scoped to it, and the selection
 * policy is the one decision over what it returns (`docs/architecture.md`). The one exception is a partial grant's
 * selection ([AndroidGalleryReader.selectedItems]): there the member's hand-picked selection is the gallery, wherever
 * a selected photo is stored (`changes/archive/2026-10-15-limited-selection-decides`).
 *
 * It is ONE scope ([SQL]), the event albums' folders in [ALBUM_ROOT] included: a received photo filed there is kept out
 * of the member's share as every received photo is — by the record of its import, or its mark after a reinstall —
 * never by its folder (`changes/archive/2026-10-11-close-share-back-gaps`, which retired the separate candidate scope of
 * `changes/archive/2026-09-30-android-event-album` D8).
 *
 * A path is MediaStore's `RELATIVE_PATH` — volume-relative, so an SD card's `DCIM` counts as the phone's own does.
 * Matched ignoring case, as SQLite's `LIKE` does for ASCII, so [isAlbumFolder] agrees with the fragments on every path.
 */
internal object DefaultGallery {

    private const val PREFIX = "DCIM/"

    /** The folder the event albums are created in, one folder each. */
    const val ALBUM_ROOT: String = "DCIM/SnapSync/"

    /** The selection fragment a MediaStore query narrows to the whole default gallery with. */
    const val SQL: String = "relative_path LIKE '$PREFIX%'"

    /** Whether [relativePath] lies in [ALBUM_ROOT] — an event album's folder, or the root itself. */
    fun isAlbumFolder(
        relativePath: String?,
    ): Boolean = relativePath != null && relativePath.startsWith(ALBUM_ROOT, ignoreCase = true)
}
