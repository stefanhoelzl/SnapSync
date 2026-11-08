package app.snapsync.query.push

import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.event.EventId
import app.snapsync.domain.membership.MembershipRead
import app.snapsync.domain.push.MemberOf
import app.snapsync.domain.push.PushTailVerdict
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log

private val log = Log("PushTail")

/**
 * Whether a silent push's wake joins the tail ([PushTailVerdict]), over the membership the push's entry point has just
 * re-read. Each answer is logged by name — an unreadable membership among them, the one that is a reason to look at
 * the device's lock state.
 */
class PushTailLookup(private val membership: MembershipRepository) {

    /** The verdict for a push naming [pushed] (`null`: the payload names no event). */
    suspend fun of(pushed: EventId?): PushTailVerdict =
        PushTailVerdict.of(pushed, memberOf(membership.state.value)).also {
            log.i("silent push weighed for the tail", "event" of pushed, "verdict" of it)
        }

    private fun memberOf(read: MembershipRead): MemberOf = when (read) {
        is MembershipRead.Joined -> MemberOf.Event(read.membership.event.id)
        is MembershipRead.NotJoined -> MemberOf.Nothing
        is MembershipRead.Unavailable -> MemberOf.Unreadable
    }
}
