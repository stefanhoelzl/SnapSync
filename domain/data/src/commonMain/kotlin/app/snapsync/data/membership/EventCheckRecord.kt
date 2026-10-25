package app.snapsync.data.membership

import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.StoreWrite
import app.snapsync.data.store.Stored
import app.snapsync.domain.event.EventId
import app.snapsync.domain.membership.CheckedAt
import app.snapsync.domain.time.Timestamp
import app.snapsync.ports.DbResult
import kotlin.time.Instant

/**
 * When a background wake last read the server about an event, per kind of read (decision record
 * `changes/timely-background-receiving`, D4–D5) — facts only: whether a read is due is the domain's `CheckCadence`.
 * The times are forgotten with the membership ([MembershipRepository.leave]).
 */
class EventCheckRecord(private val db: SharedDatabase) {

    /** When [kind] of [event] was last read. */
    fun last(kind: EventCheck, event: EventId): CheckedAt {
        val stored = Stored.Valid(event)
        return when (val read = db.execute { it.eventCheckQueries.selectLastCheck(stored, kind.key).executeAsList() }) {
            is DbResult.Ok -> read.value.map(::checkedAt).firstOrNull() ?: CheckedAt.Never
            is DbResult.Failed -> CheckedAt.Unreadable
        }
    }

    /** [kind] of [event] was read [at]. */
    fun stamp(kind: EventCheck, event: EventId, at: Timestamp): StoreWrite {
        val millis = at.at.toEpochMilliseconds()
        return written(db.execute { it.eventCheckQueries.stampCheck(Stored.Valid(event), kind.key, millis) })
    }

    private fun written(written: DbResult<*>): StoreWrite = when (written) {
        is DbResult.Ok -> StoreWrite.Written
        is DbResult.Failed -> StoreWrite.Failed(written.detail)
    }

    private fun checkedAt(millis: Long): CheckedAt = CheckedAt.At(Timestamp.of(Instant.fromEpochMilliseconds(millis)))
}

/** A kind of read a wake makes of an event; [key] is how the record stores it — runtime identity, never renamed. */
enum class EventCheck(val key: String) {
    /** The event's union: others' new photos. */
    PHOTOS("photos"),

    /** The event's state: whether its photos are deleted, its name, its members. */
    STATE("close"),
}
