package app.snapsync.query.encryption

import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.KeyFact
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.invite.InviteText
import app.snapsync.domain.membership.MembershipRead
import app.snapsync.format.InviteLinkText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * What the screen reads of the joined event's key: whether it is held, and the invite that carries it. It follows
 * the membership and the key slot as they are re-read at every entry, and never reads on its own. [scope] is the
 * process's: it keeps it following.
 */
class EventKeyQuery(
    private val membership: MembershipRepository,
    private val keys: EventKeyStore,
    scope: CoroutineScope,
) : InviteRead {

    /** Whether the joined event's key is held. Only [KeyPresence.Lost] stops anything. */
    val presence: StateFlow<KeyPresence> = combine(membership.state, keys.state, ::presenceOf)
        .stateIn(scope, SharingStarted.Eagerly, presenceOf(membership.state.value, keys.state.value))

    override fun invite(): InviteText? = inviteOf(membership.state.value, keys.state.value)
}

/**
 * The joined event's invite as a member shares it, read now: `null` while none is joined. An encrypted event's carries
 * its key and is offered only whole — while the key is not held, there is no invite; a plain event's carries none. Read
 * at the moment it is asked, so it always belongs to the membership read beside it.
 */
interface InviteRead {
    fun invite(): InviteText?
}

private fun presenceOf(read: MembershipRead, fact: KeyFact): KeyPresence = KeyPresence.of(read.held(), fact)

private fun inviteOf(read: MembershipRead, fact: KeyFact): InviteText? = read.held()?.let { joined ->
    when (joined.event.encryption) {
        Encryption.Plain -> InviteLinkText.format(InviteLink(joined.event.id, null))
        is Encryption.Encrypted -> (KeyPresence.of(joined, fact) as? KeyPresence.Held)
            ?.let { InviteLinkText.format(InviteLink(joined.event.id, it.key.key)) }
    }
}

private fun MembershipRead.held() = when (this) {
    is MembershipRead.Joined -> membership
    is MembershipRead.NotJoined, is MembershipRead.Unavailable -> null
}
