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
     * [name] is the (required, non-null) event name; [startsAt] is the event's **start date** — a
     * canonical UTC `…Z` string, likewise required and non-null. It is both the cutoff row's default and
     * its **floor** (capability `photo-selection-policy`).
     *
     * A details response lacking **either** is a transient [Failed], never a [Found] with a null name
     * (the event-album title needs one) nor one with an invented `startsAt` (a defaulted floor is a
     * *lowered* floor — the one direction the design forbids).
     */
    data class Found(val name: String, val startsAt: String) : JoinLoad
    data object NotFound : JoinLoad
    data object Failed : JoinLoad
}
