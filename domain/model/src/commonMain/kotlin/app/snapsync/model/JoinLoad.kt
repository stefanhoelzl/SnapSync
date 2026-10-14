package app.snapsync.model

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.service.ServiceFault

/**
 * The outcome of fetching an event's details for the join gate — the gate-facing mirror of the
 * `EventDirectory` port's `EventLookup` (adapted by `feature/membership`'s `toJoinLoad`). Seated in
 * `model/` (migration step 9): the gate lives in `:domain:presentation`, which may reference only the
 * command bundle, feature read-model types, and `model/` — never `ports/` — so the vocabulary the
 * injected `loadJoinDetails` read returns must be nameable from here. The gate MUST tell a
 * **missing** event (block) from a **transient** failure (retry).
 */
sealed interface JoinLoad {
    /**
     * [name] is the (required, non-null) event name; [startsAt] is the event's **start date** and [endsAt] its
     * **end date** — canonical UTC `…Z` strings, all required and non-null. [startsAt] is both the range row's lower
     * default and its **floor**; [endsAt] is both its upper default and its **ceiling**. [deletesAt] is when the
     * event's shared photos are deleted — the retention deadline the join persists, and the witness the self-leave
     * later depends on.
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
        val startsAt: EventStart,
        val endsAt: EventEnd,
        val deletesAt: DeletesAt,
        /**
         * The event's completion state — see [EventCompletionState]. Defaults to not completed.
         */
        val completion: EventCompletionState = EventCompletionState.OPEN,
        /** An ENCRYPTED event's key id; `null` for a plain event (the encrypted file format, `docs/architecture.md`). */
        val keyId: String? = null,
        /** Until when the event stays open; `null` when not served or unreadable. */
        val openUntil: OpenUntil? = null,
    ) : JoinLoad
    data object NotFound : JoinLoad

    /** The details could not be read; [fault] is how the service failed, as the guest is told it. */
    data class Failed(
        val fault: ServiceFault = ServiceFault.UNREACHABLE,
        /** When the service is being updated, how many seconds it suggests waiting before asking again; else `null`. */
        val retryAfterSeconds: Long? = null,
    ) : JoinLoad

    /**
     * The link does not open this event (the encrypted file format, `docs/architecture.md`): the event is encrypted
     * and the link carried no key, or another one — a link cut short in sharing, or a plain event's link carrying a
     * key. A user acts on it by opening the whole invite again, so it is told apart from [NotFound].
     */
    data object WrongLink : JoinLoad
}
