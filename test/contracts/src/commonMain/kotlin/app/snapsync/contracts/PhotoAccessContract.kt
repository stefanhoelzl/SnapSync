package app.snapsync.contracts

import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryGrant
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

/** The app's gallery as a clause receives it: the grant it reports. */
class PhotoAccess(val gallery: Gallery)

/**
 * What the app's gallery promises about the grant it reports (`docs/architecture.md` — this list IS the specification
 * of the port's obligations): [Gallery.grant] reads what the platform says, and never an older answer than [Gallery.access]
 * gave — every access read publishes there.
 *
 * Only the reading is contracted: `SystemUi.openSettings` hands the user to another surface, and what the user
 * chooses there is read back only afterwards — no outcome a run can reach
 * (`docs/architecture.md`, "An authorization the process cannot give itself is a precondition of the run").
 * Asking for access is contracted by `GalleryContract`.
 */
object PhotoAccessContract : Contract<PhotoAccessState, PhotoAccess>("PhotoAccess") {

    override val clauses = clauses {

        clause(
            "NO_GRANT_READS_AS_NOT_GRANTED",
            PhotoAccessState.NO_GRANT,
            covers = cells {
                oneOf {
                    on<Gallery>().emits(Gallery::grant).with(LibraryGrant.NOT_DETERMINED)
                    on<Gallery>().emits(Gallery::grant).with(LibraryGrant.DENIED)
                }
            },
        ) { access ->
            val status = access.gallery.grant.value
            assertTrue(
                status == LibraryGrant.NOT_DETERMINED || status == LibraryGrant.DENIED,
                "a process holding no grant reads undetermined or denied, got $status",
            )
        }

        clause(
            "NEVER_ASKED_READS_UNDETERMINED",
            PhotoAccessState.NEVER_ASKED,
            covers = cells {
                on<Gallery>().emits(Gallery::grant).with(LibraryGrant.NOT_DETERMINED)
            },
        ) { access ->
            assertEquals(LibraryGrant.NOT_DETERMINED, access.gallery.grant.value, "never asked is still undecided")
        }

        clause(
            "REFUSED_READS_DENIED",
            PhotoAccessState.REFUSED,
            covers = cells {
                on<Gallery>().emits(Gallery::grant).with(LibraryGrant.DENIED)
            },
        ) { access ->
            assertEquals(LibraryGrant.DENIED, access.gallery.grant.value, "asked and refused is denied")
        }

        clause(
            "PARTIAL_READS_LIMITED",
            PhotoAccessState.PARTIAL,
            covers = cells {
                on<Gallery>().emits(Gallery::grant).with(LibraryGrant.LIMITED)
            },
        ) { access ->
            assertEquals(LibraryGrant.LIMITED, access.gallery.grant.value, "a partial grant is its own answer")
        }

        clause(
            "AN_ACCESS_READ_IS_WHAT_THE_GRANT_HOLDS",
            PhotoAccessState.GRANTED,
            covers = cells {
                on<Gallery>().emits(Gallery::grant).with(LibraryGrant.GRANTED)
                on<GalleryReader>().answers(GalleryReader::access).with(LibraryGrant.GRANTED)
            },
        ) { access ->
            val read = access.gallery.access()
            assertEquals(read, access.gallery.grant.value, "the grant never holds an older answer than a read gave")
        }

        clause(
            "GRANTED_READS_GRANTED",
            PhotoAccessState.GRANTED,
            covers = cells {
                on<Gallery>().emits(Gallery::grant).with(LibraryGrant.GRANTED)
            },
        ) { access ->
            assertEquals(LibraryGrant.GRANTED, access.gallery.grant.value)
        }
    }
}
