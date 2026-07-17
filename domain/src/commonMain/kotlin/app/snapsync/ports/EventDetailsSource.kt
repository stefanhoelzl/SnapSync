package app.snapsync.join

/**
 * The outcome of fetching an event's details (capability `join-event`) — the app's ONE
 * `GET /events/:id` client, serving both the join gate and the best-effort name refresh (which reads
 * [Found.name] and treats every other outcome as "no name this time"). The gate MUST tell
 * a **missing** event (404 → block the join, an invalid/expired invite) apart from a **transient**
 * failure (network/5xx → offer Retry). [Found.name] is **required and non-null**: an event always has a
 * name (the backend enforces name-required on create, capability `event-creation`), so a `200` lacking a
 * name is a malformed/transient response mapped to [Failed], never a nameless [Found] — the event-album
 * feature (capability `event-album`) titles the album from this name, so the gate never yields a null one.
 */
sealed interface EventDetails {
    /**
     * [name] is the (required, non-null) event name; [startsAt] is the event's **start date** — the host's
     * statement of when the event began (capability `event-creation`).
     *
     * [startsAt] is **required and non-null**, like [name]. It is always present on a `200`: the backend
     * rejects a non-canonical one on create and synthesizes one from `createdAt` for markers written
     * before it existed, so the app never sees a null. A `200` lacking it is therefore malformed /
     * transient → [Failed], never a [Found] with an invented one — [startsAt] is a **floor** on this
     * membership's cutoff (capability `photo-selection-policy`), and a client that defaulted it would be
     * silently *lowering* that floor. Failing loudly and retrying is the only safe reading.
     */
    data class Found(val name: String, val startsAt: String) : EventDetails
    data object NotFound : EventDetails
    data object Failed : EventDetails
}

/**
 * Fetches an event's details by id for the join confirmation gate. Non-throwing: a transport/parse
 * error maps to [EventDetails.Failed], never an exception (the gate reduces it to a retryable state).
 */
interface EventDetailsSource {
    suspend fun fetch(eventId: String): EventDetails
}
