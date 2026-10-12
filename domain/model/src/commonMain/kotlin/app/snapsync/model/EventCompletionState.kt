package app.snapsync.model

/**
 * Where an event stands at the end of its life, as the backend's event details report
 * it. An event no longer closes: it is open until its photos are deleted.
 *
 * - [completed] — its photos are deleted; the backend keeps its record until its deletion date only so a device still
 *   in it hears "completed" rather than "not found". The membership is then shown as deleted, never left on its own.
 * - [members] — the event's present members, as served; `null` when the backend did not report them.
 */
data class EventCompletionState(
    val completed: Boolean,
    val members: MemberCounts? = null,
) {
    companion object {
        val OPEN = EventCompletionState(completed = false)
    }
}
