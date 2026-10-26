package app.snapsync.data.crypto

import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.KeyFact
import app.snapsync.domain.event.KeyToKeep
import app.snapsync.domain.membership.ConfirmedNotJoined
import app.snapsync.domain.membership.DevReset
import app.snapsync.domain.membership.KeptKey
import app.snapsync.domain.membership.LeftMembership
import app.snapsync.domain.membership.MintsKeptKey
import app.snapsync.format.EventKeyText
import app.snapsync.inbound.Log
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.WriteOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * **The joined event's key, on this device** (the encrypted file format, `docs/architecture.md`): kept in the secure
 * store's shared slot ([EncryptionSlots.EVENT_KEY]) — never in a row, a log, or a request; the backend holds only its
 * id. One slot: single active membership is the current contract, so a join keeps the joined event's key over whatever
 * was there, and a leave, a sweep of a failed leave's leftovers or the rig's reset forgets it — each with the evidence
 * that it may.
 *
 * [state] is seeded by its first use and re-read by [reload] at every entry, as the membership is: another process's
 * write is not otherwise seen here. A locked phone reads [KeyFact.Unreadable], never "no key".
 */
@OptIn(MintsKeptKey::class)
class EventKeyStore(private val store: SecureStore, private val ids: EventKeyIds) {

    private val held = lazy { MutableStateFlow(read().also { reported(KeyFact.Absent, it) }) }

    /** What the slot held at the last read. */
    val state: StateFlow<KeyFact> get() = held.value

    /** Read the slot again into [state]. */
    fun reload(): KeyFact = read().also { settle(it) }

    /**
     * Keep [keep] for [event] — its key, or for a plain event the slot cleared — before the event counts this device.
     * A key the store refuses fails the join; a clear it refuses does not: an earlier event's key no membership names
     * is inert, and the next join, leave or reset replaces it.
     */
    fun keep(keep: KeyToKeep, event: EventId): KeepOutcome = when (keep) {
        KeyToKeep.None -> cleared().let { KeepOutcome.Kept(KeptKey(event)) }
        is KeyToKeep.Key -> written(
            store.write(EncryptionSlots.EVENT_KEY, EventKeyText.encode(keep.key.key)),
            keep.key,
            event,
        )
    }

    /** The membership ended: its key goes with it. */
    fun forget(@Suppress("UNUSED_PARAMETER") because: LeftMembership) = cleared()

    /** No membership names a key: one a failed leave left behind goes. */
    fun forget(@Suppress("UNUSED_PARAMETER") because: ConfirmedNotJoined) = cleared()

    /** The rig resets the device's state. */
    fun forget(@Suppress("UNUSED_PARAMETER") because: DevReset) = cleared()

    private fun written(outcome: WriteOutcome, key: IdentifiedKey, event: EventId): KeepOutcome = when (outcome) {
        WriteOutcome.Ok -> kept(key, event)
        is WriteOutcome.Failed -> KeepOutcome.Refused(outcome.detail).also { reload() }
    }

    private fun kept(key: IdentifiedKey, event: EventId): KeepOutcome {
        settle(KeyFact.Kept(key))
        return KeepOutcome.Kept(KeptKey(event))
    }

    /** Deleting nothing is fine; a refused delete is read back as what the slot still holds. */
    private fun cleared(): KeyFact = when (store.delete(EncryptionSlots.EVENT_KEY)) {
        WriteOutcome.Ok -> KeyFact.Absent.also { settle(it) }
        is WriteOutcome.Failed -> reload()
    }

    private fun read(): KeyFact = when (val read = store.read(EncryptionSlots.EVENT_KEY)) {
        is SecureStoreRead.Found -> ids.factOf(read.value)
        SecureStoreRead.Absent -> KeyFact.Absent
        is SecureStoreRead.Unavailable -> KeyFact.Unreadable
    }

    private fun settle(next: KeyFact) {
        reported(held.value.value, next)
        held.value.value = next
    }
}

/** What keeping a join's key came to. */
sealed interface KeepOutcome {
    /** Kept — or, for a plain event, the slot cleared: the join may enrol. */
    class Kept(val evidence: KeptKey) : KeepOutcome

    /** The store refused the key: the join fails before the event counts this device. */
    class Refused(val detail: String) : KeepOutcome
}

private val log = Log("EventKeyStore")

/** What a stored [text] is: a key, with its id, or something that is no key. */
private fun EventKeyIds.factOf(text: String): KeyFact =
    EventKeyText.decode(text)?.let { KeyFact.Kept(identify(it)) } ?: KeyFact.Invalid

/** A slot that turns to something that is no key is said once, when it does — naming nothing of what it holds. */
private fun reported(previous: KeyFact, next: KeyFact) = when (next) {
    KeyFact.Invalid -> when (previous) {
        KeyFact.Invalid -> Unit
        is KeyFact.Kept, KeyFact.Absent, KeyFact.Unreadable -> log.unlabelled.w("the stored event key is no key")
    }
    is KeyFact.Kept, KeyFact.Absent, KeyFact.Unreadable -> Unit
}
