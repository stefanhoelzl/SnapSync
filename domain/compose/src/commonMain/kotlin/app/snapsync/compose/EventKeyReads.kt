package app.snapsync.compose

import app.snapsync.model.EventConfig
import app.snapsync.model.KeyPresence
import app.snapsync.services.crypto.EventKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * **What the screen reads of the joined event's key** (the encrypted file format, `docs/architecture.md`), for readers
 * outside the core, which see no service type.
 */
class EventKeyReads internal constructor(keys: EventKeys, membership: StateFlow<EventConfig?>, scope: CoroutineScope) {
    private val rereads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * The joined event's key as its invite carries it, while the membership is ENCRYPTED: read from the secure store
     * each time the membership changes and on every [reread], `null` for a plain one or while the store cannot be read
     * (a locked device, a lost key) — and then no invite is offered (capability `manage-membership`).
     */
    val inviteKey: StateFlow<String?> = keys.inviteKeyOf(membership, rereads, scope)

    /**
     * Whether this device holds the joined event's key (capability `sync-status`): re-read when the membership changes,
     * at every foreground and after a reopened invite gave it back — only [KeyPresence.Lost] is shown.
     */
    val presence: StateFlow<KeyPresence> = keys.presenceOf(membership, rereads, scope)

    /** Read [presence] and [inviteKey] again — a foreground, or a key kept from a reopened invite. */
    fun reread() {
        rereads.tryEmit(Unit)
    }
}
