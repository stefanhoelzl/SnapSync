package app.snapsync.data.membership

import app.snapsync.domain.event.EventKeyId
import app.snapsync.domain.invite.InviteKey

/**
 * Whether an invite's key opens an event: a plain event wants no key, an encrypted one exactly its own. MIGRATING: the
 * event keys are the old zones' until they move (phase 4.3); the composition answers this over them.
 */
interface InviteKeyCheck {
    fun opens(key: InviteKey?, keyId: EventKeyId?): Boolean
}
