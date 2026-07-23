package app.snapsync.model

/**
 * The outcome of fetching an event's details for the join gate — the gate-facing mirror of the
 * `EventDirectory` port's `EventDetails` (adapted by `feature/membership`'s `toJoinLoad`). Seated in
 * `model/` (migration step 9): the gate lives in `:ui:presentation`, which may reference only the
 * command bundle, feature read-model types, and `model/` — never `ports/` — so the vocabulary the
 * injected `loadJoinDetails` read returns must be nameable from here. The gate MUST tell a
 * **missing** event (block) from a **transient** failure (retry).
 */
sealed interface JoinLoad {
    /**
     * [name] is the (required, non-null) event name; [startsAt] is the event's **start date** and [endsAt]
     * its **end date** — canonical UTC `…Z` strings, all required and non-null. [startsAt] is both the
     * range row's lower default and its **floor**; [endsAt] is both its upper default and its **ceiling**
     * (capability `photo-selection-policy`). [deletesAt] is when the event's shared photos are deleted
     * (capability `event-limits`) — the retention deadline the gate states before confirm, and the second
     * witness the self-leave later depends on (capability `leave-event`).
     *
     * A details response lacking **any** of the four is a transient [Failed], never a [Found] with a null
     * name (the event-album title needs one) nor one with an invented `startsAt`/`endsAt` (a defaulted
     * floor is a *lowered* floor and a defaulted ceiling a *raised* one — the directions the design
     * forbids) nor an invented `deletesAt` (which would decide whether a membership is destroyed). The
     * backend always serves all four on a `200` (an incomplete marker is `gone` → 404), so the app never
     * sees a null.
     */
    data class Found(
        val name: String,
        val startsAt: String,
        val endsAt: String,
        val deletesAt: String,
    ) : JoinLoad
    data object NotFound : JoinLoad
    data object Failed : JoinLoad
}
