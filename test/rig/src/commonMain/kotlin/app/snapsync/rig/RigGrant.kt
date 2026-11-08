package app.snapsync.rig

import app.snapsync.domain.sharing.PhotoGrant

/**
 * The photo grant as the control protocol spells it — the platform's names (`GET /device/gallery`'s `grant`, the OS
 * extension's `grantDependent`), which journeys and skills read. The core's [PhotoGrant] is the domain's word; this is
 * the protocol's, so a rename in the core never reaches a client.
 */
object RigGrant {
    fun name(grant: PhotoGrant): String = when (grant) {
        PhotoGrant.UNDECIDED -> "NOT_DETERMINED"
        PhotoGrant.REFUSED -> "DENIED"
        PhotoGrant.LIMITED -> "LIMITED"
        PhotoGrant.FULL -> GRANTED
    }

    /** A whole-library grant, as the protocol names it. */
    const val GRANTED: String = "GRANTED"
}
