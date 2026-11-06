package app.snapsync.domain.sharing

/**
 * **The photo-library grant, as the screen reads it.** [UNDECIDED] has never been asked, so the screen can ask;
 * [REFUSED] was refused, or is not the member's to change, so only Settings can grant it; [LIMITED] is a partial grant,
 * whose hand-picked selection is the member's gallery; [FULL] is the whole library. [PhotoAccess] is the read's own
 * vocabulary and cannot serve here: it tells no undecided grant from a refused one.
 */
enum class PhotoGrant {
    UNDECIDED,
    REFUSED,
    LIMITED,
    FULL,
    ;

    /** Whether the app may read photos at all — under a partial grant, the selection. */
    val readsPhotos: Boolean get() = this == LIMITED || this == FULL
}
