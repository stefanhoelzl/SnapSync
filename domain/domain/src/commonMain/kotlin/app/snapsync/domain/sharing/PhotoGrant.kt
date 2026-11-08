package app.snapsync.domain.sharing

/**
 * **The photo-library grant** — the core's one word for it. [UNDECIDED] has never been asked, so the screen can ask;
 * [REFUSED] was refused, or is not the member's to change, so only Settings can grant it; [LIMITED] is a partial grant,
 * whose hand-picked selection is the member's gallery; [FULL] is the whole library.
 *
 * Data translates the platform's grant into this once (`PhotoGrants`); everything else reads it, or its [access].
 */
enum class PhotoGrant {
    UNDECIDED,
    REFUSED,
    LIMITED,
    FULL,
    ;

    /** Whether the app may read photos at all — under a partial grant, the selection. */
    val readsPhotos: Boolean get() = this == LIMITED || this == FULL

    /** The grant as a read of the member's photos takes it: undecided and refused both leave nothing to read. */
    val access: PhotoAccess
        get() = when (this) {
            FULL -> PhotoAccess.FULL
            LIMITED -> PhotoAccess.LIMITED
            UNDECIDED, REFUSED -> PhotoAccess.NONE
        }
}
