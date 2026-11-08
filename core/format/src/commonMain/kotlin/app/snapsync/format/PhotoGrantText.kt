package app.snapsync.format

import app.snapsync.domain.sharing.PhotoGrant

/** The photo-library grant as text a reader outside the app depends on. */
object PhotoGrantText {
    /**
     * The grant as a bug report names it (`photo_permission`): the spellings every report has carried — the operator's
     * reports are filtered by them, so a rename of [PhotoGrant] never reaches them.
     */
    fun report(grant: PhotoGrant): String = when (grant) {
        PhotoGrant.UNDECIDED -> "NOT_DETERMINED"
        PhotoGrant.REFUSED -> "DENIED"
        PhotoGrant.LIMITED -> "LIMITED"
        PhotoGrant.FULL -> "FULL"
    }
}
