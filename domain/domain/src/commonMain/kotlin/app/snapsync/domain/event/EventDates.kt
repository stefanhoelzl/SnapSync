package app.snapsync.domain.event

import app.snapsync.domain.time.Timestamp
import kotlin.jvm.JvmInline

// The EVENT's dates: one role type per date, each holding a [Timestamp], so a date can never be passed as a date it
// is not. Confusing two of them is a compile error, which is the point: `add-event-date-range` once dropped the
// ceiling at two of four consumers because `Contribution.Since -> c.cutoff` compiled as readily as the right
// destructure, and passing an event's start where a membership's floor is wanted silently LOWERS the floor.

/**
 * The event's start — host-chosen at creation, immutable, shared by every member. The default a member's
 * [app.snapsync.domain.sharing.CaptureCutoff] starts at, and the moment before which nothing is shared; never itself a
 * membership's bound.
 */
@JvmInline
value class EventStart(val at: Timestamp) : Comparable<EventStart> {
    override fun compareTo(other: EventStart): Int = at.compareTo(other.at)
}

/**
 * The event's end — host-chosen at creation, immutable: the capture-date ceiling every member's own ceiling starts at.
 * It closes nothing: an event stays open until its [OpenUntil].
 */
@JvmInline
value class EventEnd(val at: Timestamp) : Comparable<EventEnd> {
    override fun compareTo(other: EventEnd): Int = at.compareTo(other.at)
}

/**
 * Until when the event stays open — its [EventEnd], or, for an event created after its end, the day its host chose
 * (`changes/back-dated-events`, D1). The date its lifecycle counts from; it closes nothing either.
 */
@JvmInline
value class OpenUntil(val at: Timestamp) : Comparable<OpenUntil> {
    override fun compareTo(other: OpenUntil): Int = at.compareTo(other.at)
}

/**
 * When the backend deletes the event's shared data — derived **server-side** and served with the event's details. The
 * device stores it and never computes it. Its own type because it is the one date that decides whether a membership
 * is shown as deleted: the most expensive role to confuse with any other.
 */
@JvmInline
value class DeletesAt(val at: Timestamp) : Comparable<DeletesAt> {
    override fun compareTo(other: DeletesAt): Int = at.compareTo(other.at)
}
