package app.snapsync.model

import app.snapsync.domain.service.ServiceFault

/**
 * The outcome of a `PATCH /events/:id` rename call — a closed set the use-case maps to `RenameStatus`. The twin of
 * [CreateOutcome], and deliberately the same three shapes.
 */
sealed interface RenameOutcome {
    /**
     * `200` — the backend rewrote the marker; [name] is the **stored** name it echoed back.
     *
     * The echo is what gets persisted, never the string the member typed: the backend trims, so echoing
     * is the only way the client and the marker cannot disagree about whitespace. (The same reason
     * [CreateOutcome.Created] carries a name rather than the caller reusing its own input.)
     */
    data class Renamed(val name: String) : RenameOutcome

    /** `400` — the backend rejected the name (empty after trimming, or over 100 characters). */
    data object InvalidName : RenameOutcome

    /**
     * Any other non-2xx, transport, or parse failure — **including `404`**. [fault] is how the service failed, as the
     * member is told it; `null` for an answer from a working service — a `404` among them — told generically.
     *
     * A `404` is deliberately NOT its own outcome. It is a *single* witness that the event is gone, and the self-leave
     * requires **two** independent witnesses — the backend's `404` **and** this membership's own locally-stored
     * `deletesAt` having passed — precisely so that no backend fault can manufacture both and destroy every membership
     * in the install base at once. The `EventConfig` is the only record of a join, so that loss is unrecoverable.
     *
     * Giving the `404` a distinct outcome here would give it a distinct user-facing meaning, and a
     * surfaced meaning invites a future change to *act* on it — opening a second route to the destructive
     * outcome `MembershipRefresh` exists to gate. Collapsing it costs the member a slightly vaguer error
     * message and nothing else: the standing foreground refresh reaches `ABSENT` on its own terms.
     */
    data class Transient(val fault: ServiceFault? = null) : RenameOutcome

    /**
     * The service answered that the event's photos were deleted — its positive word, unlike a `404`: the member is
     * told the event is over, and the membership is marked so.
     */
    data object EventOver : RenameOutcome
}
