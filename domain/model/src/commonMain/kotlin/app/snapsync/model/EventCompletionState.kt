package app.snapsync.model

/**
 * Where an event stands in its early completion (decision record `changes/early-event-completion`), as the backend's
 * event details report it.
 *
 * - [OPEN] — anyone may still join, rename, and change what they share.
 * - [CLOSED] — every member still in it has settled what it shares (or the clock ran out): nobody joins, nothing
 *   about it changes, and each member leaves on its own once it has everything. [members] is `null` when the
 *   backend did not report them.
 * - [COMPLETED] — its photos are deleted; the backend keeps its record until its deletion date only so a device
 *   still in it hears "completed" rather than "not found". A member leaves on it.
 *
 * [members] carries the waiting line's counts in every state they were served in.
 */
data class EventCompletionState(
    val closed: Boolean,
    val completed: Boolean,
    val members: MemberCounts? = null,
) {
    companion object {
        val OPEN = EventCompletionState(closed = false, completed = false)
    }
}
