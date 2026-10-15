package app.snapsync.data.membership

import app.snapsync.domain.membership.ConfirmedNotJoined
import app.snapsync.domain.membership.Membership

/**
 * The one read of this device's membership. "Not joined" is only ever a store that answered with no membership; one
 * that could not be read — a locked device, a membership still in its legacy file, a row the domain refuses — is
 * [Unavailable], and whoever acts on absence defers.
 */
sealed interface MembershipRead {
    data class Joined(val membership: Membership) : MembershipRead

    class NotJoined(val evidence: ConfirmedNotJoined) : MembershipRead

    /** The membership could not be read now. [detail] is diagnostics only. */
    data class Unavailable(val detail: String) : MembershipRead
}
