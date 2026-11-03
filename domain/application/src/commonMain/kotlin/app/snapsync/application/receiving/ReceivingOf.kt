package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.receiving.ReceivingVerdict

/**
 * Whether [Membership.Active] receives — by its direction and its event's key, as the key slot was last read: the
 * one place a [app.snapsync.domain.receiving.ReceivingMembership] comes from, read on demand at every trigger, never
 * held at composition.
 */
class ReceivingOf(private val keys: EventKeyStore) {
    @EntryPointCall
    operator fun invoke(active: Membership.Active): ReceivingVerdict =
        ReceivingVerdict.of(active, KeyPresence.of(active, keys.state.value))
}
