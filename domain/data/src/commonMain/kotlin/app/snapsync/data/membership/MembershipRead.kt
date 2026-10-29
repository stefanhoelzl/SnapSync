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

    /**
     * The membership could not be read now. [detail] is diagnostics only.
     *
     * [refused] when the store answered with a row this build refuses — a share range its rules forbid, an inverted
     * event, a blank name. No path in this build writes one; an older build's row read against tightened rules is how
     * one would arrive. Nothing is shared or received for it, as for any unreadable membership, but read again it
     * answers the same — so a join replaces it, the member's one way back into an event, where any other unreadable
     * membership fails the join.
     */
    data class Unavailable(val detail: String, val refused: Boolean = false) : MembershipRead
}
