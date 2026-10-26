package app.snapsync.query.encryption

import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.event.KeyFact
import app.snapsync.domain.event.KeyPresence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * What the screen reads of the joined event's key: whether it is held (and so the key its invite carries). It follows
 * the membership and the key slot as they are re-read at every entry, and never reads on its own. [scope] is the
 * process's: it keeps it following.
 */
class EventKeyQuery(membership: MembershipRepository, keys: EventKeyStore, scope: CoroutineScope) {

    /** Whether the joined event's key is held. Only [KeyPresence.Lost] stops anything. */
    val presence: StateFlow<KeyPresence> = combine(membership.state, keys.state, ::presenceOf)
        .stateIn(scope, SharingStarted.Eagerly, presenceOf(membership.state.value, keys.state.value))
}

private fun presenceOf(read: MembershipRead, fact: KeyFact): KeyPresence = KeyPresence.of(read.held(), fact)

private fun MembershipRead.held() = when (this) {
    is MembershipRead.Joined -> membership
    is MembershipRead.NotJoined, is MembershipRead.Unavailable -> null
}
