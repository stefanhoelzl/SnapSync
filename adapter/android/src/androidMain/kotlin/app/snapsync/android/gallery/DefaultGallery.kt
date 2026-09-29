package app.snapsync.android.gallery

/**
 * The member's **default gallery** on Android (capability `photo-sharing`): the `DCIM` folder and every folder under
 * it, on every storage volume — where camera apps save. Every own-candidate read of the gallery is scoped to it, and the
 * selection policy is the one decision over what it returns (`docs/architecture.md`).
 *
 * A path is MediaStore's `RELATIVE_PATH` — volume-relative, so an SD card's `DCIM` counts as the phone's own does.
 * Matched ignoring case, as SQLite's `LIKE` does for ASCII, so [SQL] and [contains] agree on every path.
 */
internal object DefaultGallery {

    private const val PREFIX = "DCIM/"

    /** The selection fragment a MediaStore query narrows to the default gallery with. */
    const val SQL: String = "relative_path LIKE '$PREFIX%'"

    /** Whether an item at [relativePath] lies in the default gallery; an item with no path does not. */
    fun contains(relativePath: String?): Boolean = relativePath != null && relativePath.startsWith(PREFIX, ignoreCase = true)
}
