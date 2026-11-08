package app.snapsync.data.legacy

import app.snapsync.data.membership.EventCheck
import app.snapsync.data.store.LegacySource
import app.snapsync.data.store.Stored
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.domain.event.EventId
import app.snapsync.inbound.Log
import app.snapsync.ports.PrefRead
import app.snapsync.ports.Preferences

/**
 * The joined event's check times. Keys were per event, and the Preferences port cannot list keys, so only the
 * membership's event is copied — a leave always forgot its event's times, so any other event's are leftovers. A time
 * not copied only makes its next background check due at once: one extra request.
 */
internal class LegacyChecksCopy(private val preferences: Preferences) {
    private val log = Log("LegacyRecords")

    fun copy(db: LedgerDatabase) = when (val joined = joinedEvent(db)) {
        is Joined.Event -> copy(db, joined.id, timesOf(joined.id))
        Joined.None -> db.legacyImportQueries.markImported(LegacySource.CHECKS.key)
        Joined.Unreadable ->
            log.unlabelled.w("legacy check times: the membership is unreadable — retried at the next open")
    }

    /** The check times a crash left behind after their copy. */
    fun forget(db: LedgerDatabase) = when (val joined = joinedEvent(db)) {
        is Joined.Event -> forget(joined.id, EventCheck.entries)
        Joined.None, Joined.Unreadable -> Unit
    }

    private fun copy(db: LedgerDatabase, eventId: EventId, times: Map<EventCheck, PrefRead>) =
        when (times.values.any { it is PrefRead.Unavailable }) {
            true -> log.unlabelled.w("legacy check times unreadable — retried at the next open")
            false -> stamp(db, eventId, times)
        }

    private fun stamp(db: LedgerDatabase, eventId: EventId, times: Map<EventCheck, PrefRead>) {
        db.transaction {
            times.forEach { (check, read) -> stamp(db, eventId, check, read) }
            db.legacyImportQueries.markImported(LegacySource.CHECKS.key)
        }
        forget(eventId, times.keys)
    }

    private fun stamp(db: LedgerDatabase, eventId: EventId, check: EventCheck, read: PrefRead) {
        (read as? PrefRead.Value)?.let { it.value.toLongOrNull() }
            ?.let { db.eventCheckQueries.stampCheck(Stored.Valid(eventId), check.key, it) }
    }

    private fun forget(eventId: EventId, checks: Collection<EventCheck>) =
        checks.forEach { preferences.remove(checkKey(it, eventId)) }

    private fun timesOf(eventId: EventId): Map<EventCheck, PrefRead> =
        EventCheck.entries.associateWith { preferences.get(checkKey(it, eventId)) }

    private fun joinedEvent(db: LedgerDatabase): Joined =
        db.membershipQueries.selectMembership().executeAsList()
            .map { joinedOf(it.eventId) }
            .firstOrNull() ?: Joined.None

    private fun joinedOf(stored: Stored<EventId>): Joined = when (stored) {
        is Stored.Valid -> Joined.Event(stored.value)
        is Stored.Invalid -> Joined.Unreadable
    }

    private fun checkKey(check: EventCheck, eventId: EventId) = "app.snapsync.check.${check.key}.$eventId"

    /** The membership the database holds, as the check times' copy reads it. */
    private sealed interface Joined {
        class Event(val id: EventId) : Joined

        data object None : Joined

        /** A membership row whose event id spells none: its times wait, as an unreadable source does. */
        data object Unreadable : Joined
    }
}
