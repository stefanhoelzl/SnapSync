package app.snapsync.contracts

import app.snapsync.model.GalleryAccess
import app.snapsync.ports.PhotoAccessStatusSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The photo grant the process holds, as far as a clause cares. */
enum class PhotoAccessState {
    /** Undetermined or denied: the two answers the app treats alike. */
    NO_GRANT,

    /** No grant, and the process never asked for one — on a platform that keeps its own record of having asked. */
    NEVER_ASKED,

    /** No grant, and the process asked and was refused — on a platform that keeps its own record of having asked. */
    REFUSED,

    /** A full grant. */
    GRANTED,

    /** A partial grant: the member's selection of photos. */
    PARTIAL,
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

        clause(
            "NO_GRANT_READS_AS_NOT_GRANTED",
            PhotoAccessState.NO_GRANT,
            covers = cells {
                oneOf {
                    on<PhotoAccessStatusSource>().emits(
                        PhotoAccessStatusSource::permission,
                    ).with(GalleryAccess.NOT_DETERMINED)
                    on<PhotoAccessStatusSource>().emits(PhotoAccessStatusSource::permission).with(GalleryAccess.DENIED)
                }
            },
        ) { access ->
            val status = access.status.permission.value
            assertTrue(
                status == GalleryAccess.NOT_DETERMINED || status == GalleryAccess.DENIED,
                "a process holding no grant reads undetermined or denied, got $status",
            )
        }

        clause(
            "NEVER_ASKED_READS_UNDETERMINED",
            PhotoAccessState.NEVER_ASKED,
            covers = cells {
                on<PhotoAccessStatusSource>()
                    .emits(PhotoAccessStatusSource::permission)
                    .with(GalleryAccess.NOT_DETERMINED)
            },
        ) { access ->
            assertEquals(GalleryAccess.NOT_DETERMINED, access.status.permission.value, "never asked is still undecided")
        }

        clause(
            "REFUSED_READS_DENIED",
            PhotoAccessState.REFUSED,
            covers = cells {
                on<PhotoAccessStatusSource>().emits(PhotoAccessStatusSource::permission).with(GalleryAccess.DENIED)
            },
        ) { access ->
            assertEquals(GalleryAccess.DENIED, access.status.permission.value, "asked and refused is denied")
        }

        clause(
            "PARTIAL_READS_LIMITED",
            PhotoAccessState.PARTIAL,
            covers = cells {
                on<PhotoAccessStatusSource>().emits(PhotoAccessStatusSource::permission).with(GalleryAccess.LIMITED)
            },
        ) { access ->
            assertEquals(GalleryAccess.LIMITED, access.status.permission.value, "a partial grant is its own answer")
        }

        clause(
            "GRANTED_READS_GRANTED",
            PhotoAccessState.GRANTED,
            covers = cells {
                on<PhotoAccessStatusSource>().emits(PhotoAccessStatusSource::permission).with(GalleryAccess.GRANTED)
            },
        ) { access ->
            assertEquals(GalleryAccess.GRANTED, access.status.permission.value)
        }
    }
}
