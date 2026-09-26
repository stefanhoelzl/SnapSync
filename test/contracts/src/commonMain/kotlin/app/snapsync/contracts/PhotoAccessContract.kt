package app.snapsync.contracts

import app.snapsync.model.GalleryAccess
import app.snapsync.ports.PhotoAccessStatusSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The photo grant the process holds, as far as a clause cares. */
enum class PhotoAccessState {
    /** Undetermined or denied: the two answers the app treats alike. */
    NO_GRANT,

    /** A full grant. */
    GRANTED,
}

/** The photo-access adapter as a clause receives it: the permission status. */
class PhotoAccess(val status: PhotoAccessStatusSource)

/**
 * What every photo-access adapter promises (`docs/architecture.md` — this list IS the specification of
 * the ports' obligations).
 *
 * Only the status is contracted: `SystemUi.openSettings` hands the user to another surface, and what the user
 * chooses there is read back only afterwards, through the status — no outcome a run can reach
 * (`docs/architecture.md`, "An authorization the process cannot give itself is a precondition of the run").
 * Asking for access is the gallery's, contracted by `GalleryContract`.
 */
object PhotoAccessContract : Contract<PhotoAccessState, PhotoAccess>("PhotoAccess") {

    override val clauses = clauses {

        clause("NO_GRANT_READS_AS_NOT_GRANTED", PhotoAccessState.NO_GRANT) { access ->
            val status = access.status.permission.value
            assertTrue(
                status == GalleryAccess.NOT_DETERMINED || status == GalleryAccess.DENIED,
                "a process holding no grant reads undetermined or denied, got $status",
            )
        }

        clause("GRANTED_READS_GRANTED", PhotoAccessState.GRANTED) { access ->
            assertEquals(GalleryAccess.GRANTED, access.status.permission.value)
        }
    }
}
