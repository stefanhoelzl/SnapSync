package app.snapsync.compose

import app.snapsync.model.EventConfig
import app.snapsync.model.KeyPresence
import app.snapsync.services.crypto.EventKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * **What the screen reads of the joined event's key** (the encrypted file format, `docs/architecture.md`), for readers
 * outside the core, which see no service type.
 */
class EventKeyReads internal constructor(
    keys: EventKeys,
    membership: StateFlow<EventConfig?>,
    scope: CoroutineScope,
    /** The app's returns to the foreground: each one is a re-read. */
    foregroundReturns: StateFlow<Any>,
) {
    private val asked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Every re-read: one [reread] asked for, and every return to the foreground. */
    private val rereads: Flow<Unit> = merge(asked, foregroundReturns.drop(1).map { })

    /**
     * The joined event's key as its invite carries it, while the membership is ENCRYPTED: read from the secure store
     * each time the membership changes and on every [reread], `null` for a plain one or while the store cannot be read
     * (a locked device, a lost key) — and then no invite is offered.
     */
    val inviteKey: StateFlow<String?> = keys.inviteKeyOf(membership, rereads, scope)

    /**
     * Whether this device holds the joined event's key: re-read when the membership changes,

     * at every foreground and after a reopened invite gave it back — only [KeyPresence.Lost] is shown.
     */
    val presence: StateFlow<KeyPresence> = keys.presenceOf(membership, rereads, scope)

    /** Read [presence] and [inviteKey] again — a foreground, or a key kept from a reopened invite. */
    fun reread() {
        asked.tryEmit(Unit)
    }
}
