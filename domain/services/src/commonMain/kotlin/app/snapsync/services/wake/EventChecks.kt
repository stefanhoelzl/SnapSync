package app.snapsync.services.wake

import app.snapsync.domain.event.EventId
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.databases.SharedDatabase
import co.touchlab.kermit.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A request a background wake makes of an event — each bounded on its own (see [EventChecks]). */
enum class EventCheck(internal val key: String) {
    /** The event's union: others' new photos (`GET /events/:id/files`). */
    PHOTOS("photos"),

    /** The event's state: has it closed or finished (`GET /events/:id`). */
    CLOSE("close"),
}

/**
 * **When a background wake last asked the event** ("New photos are announced by a
 * silent wake, and never only by it"; decision record `changes/timely-background-receiving`, D4–D5): one time per
 * [EventCheck] and event, in the shared database — a background wake is usually a fresh process, so a time kept in
 * memory would make every wake look due.
 *
 * - [due] — a wake asks at most once per [INTERVAL]: due when no time is stored, when it cannot be read, when it lies
 *   in the future (the clock was moved back), or when it is at least [INTERVAL] old.
 * - [stamp] — **every** ask writes the time, the unbounded ones too (a push, an opening, a join) and a failed one as
 *   much as a successful one: a failing backend is asked no more often than a working one.
 * - [clear] — a leave or a reset forgets the event's times, so a re-join asks at once.
 *
 * A time that cannot be written is logged and otherwise ignored: the next wake asks again, which costs one request.
 */
class EventChecks(
    private val db: SharedDatabase,
    /** Now, off the process's `Clock` — a wall clock the user can move, which [due] allows for. */
    private val now: () -> Instant,
    private val log: Logger = Logger.withTag("EventChecks"),
) {
    /** Whether a wake may ask [eventId] for [check] now. */
    fun due(check: EventCheck, eventId: EventId): Boolean {
        val millis = runCatchingCancellable {
            db.use { it.eventCheckQueries.selectLastCheck(eventId, check.key).executeAsOneOrNull() }
        }.getOrElse { return true.also { _ -> log.w(it) { "last $check check unreadable — due" } } }
            ?: return true
        val since = now() - Instant.fromEpochMilliseconds(millis)
        return since < Duration.ZERO || since >= INTERVAL
    }

    /** Record that [eventId] was asked for [check] now. */
    fun stamp(check: EventCheck, eventId: EventId) {
        runCatchingCancellable {
            db.use { it.eventCheckQueries.stampCheck(eventId, check.key, now().toEpochMilliseconds()) }
        }.onFailure { log.w(it) { "last $check check not recorded — the next wake asks again" } }
    }

    /** Forget every time recorded for [eventId]. */
    fun clear(eventId: EventId) {
        runCatchingCancellable { db.use { it.eventCheckQueries.clearChecks(eventId) } }
            .onFailure { log.w(it) { "the check times of $eventId not forgotten" } }
    }
}

/** The least time between two asks of one event from background wakes. */
private val INTERVAL: Duration = 1.hours
