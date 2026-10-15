package app.snapsync.domain.event

import kotlin.jvm.JvmInline

/**
 * The event as this device last knew it from the server: its name, its dates, its key and its members. A refresh or a
 * rename replaces it whole; the member's own choices on it are their share, never part of it.
 */
data class Event(
    val id: EventId,
    val name: EventName,
    val range: EventRange,
    /** The day guests may join until, as the server last served it; `null` when it served none. */
    val openUntil: OpenUntil?,
    /** When the server deletes the event's photos at the latest — the offline witness of a deletion. */
    val deletesAt: DeletesAt,
    /** An encrypted event's key id; `null` for a plain event. */
    val keyId: EventKeyId?,
    /** The members' progress, as the server last served it; `null` until it has. */
    val members: MemberCounts?,
)

/** An encrypted event's key id: what an invite's key must name to open the event. */
@JvmInline
value class EventKeyId(val value: String)

/**
 * How far the event's members are: [active] members still in it, [settled] of them done sharing, and [waiting] — when
 * the server serves it — how many it still waits for.
 */
data class MemberCounts(val active: Int, val settled: Int, val waiting: Int? = null) {
    /** How many present members the event is still waiting for to finish sharing. */
    val waitingFor: Int get() = waiting ?: (active - settled).coerceAtLeast(0)
}
