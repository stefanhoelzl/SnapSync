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
     * (capability `photo-selection-policy`).
     *
     * A details response lacking **any** of the three is a transient [Failed], never a [Found] with a null
     * name (the event-album title needs one) nor one with an invented `startsAt`/`endsAt` (a defaulted
     * floor is a *lowered* floor and a defaulted ceiling a *raised* one — the directions the design
     * forbids). The backend always serves `endsAt` on a `200` (an event with no stamped end is `gone` →
     * 404), so the app never sees a null.
     */
    data class Found(val name: String, val startsAt: String, val endsAt: String) : JoinLoad
    data object NotFound : JoinLoad
    data object Failed : JoinLoad
}
