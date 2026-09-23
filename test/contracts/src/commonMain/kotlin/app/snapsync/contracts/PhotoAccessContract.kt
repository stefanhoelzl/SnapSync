package app.snapsync.contracts

import app.snapsync.model.PermissionStatus
import app.snapsync.ports.PhotoAccessRequester
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

/** The photo-access adapter as a clause receives it: one adapter implements both ports on every platform. */
class PhotoAccess(val status: PhotoAccessStatusSource, val requester: PhotoAccessRequester)

/**
 * What every photo-access adapter promises (capability `port-contracts` — this list IS the specification of
 * the ports' obligations).
 *
 * Only [PhotoAccessRequester.request] is contracted, and only once the grant is determined. Asked while
 * undetermined, it raises a system prompt that only a person can answer. `openSettings` and `choosePhotos`
 * hand the user to another surface, and what the user chooses there is read back only afterwards, through the
 * status. None of those has an outcome a run can reach (capability `port-contracts`, "An authorization the
 * process cannot give itself is a precondition of the run").
 */
object PhotoAccessContract : Contract<PhotoAccessState, PhotoAccess>("PhotoAccess") {

    override val clauses = clauses {

        clause("NO_GRANT_READS_AS_NOT_GRANTED", PhotoAccessState.NO_GRANT) { access ->
            val status = access.status.permission.value
            assertTrue(
                status == PermissionStatus.NOT_DETERMINED || status == PermissionStatus.DENIED,
                "a process holding no grant reads undetermined or denied, got $status",
            )
        }

        clause("GRANTED_READS_GRANTED", PhotoAccessState.GRANTED) { access ->
            assertEquals(PermissionStatus.GRANTED, access.status.permission.value)
        }

        clause("GRANTED_REQUEST_CHANGES_NOTHING", PhotoAccessState.GRANTED) { access ->
            access.requester.request()
            access.requester.request()
            assertEquals(
                PermissionStatus.GRANTED,
                access.status.permission.value,
                "a request after the grant is determined asks nothing and changes nothing, however often it runs",
            )
        }
    }
}
