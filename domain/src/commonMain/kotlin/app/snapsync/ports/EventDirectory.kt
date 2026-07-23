package app.snapsync.ports

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
     * [name] is the (required, non-null) event name; [startsAt] is the event's **start date** and [endsAt]
     * its **end date** — the host's statement of the capture WINDOW (capability `event-creation`), which
     * bounds only which photos may be uploaded and closes nothing. [deletesAt] is when the backend
     * deletes the event's shared data (capability `event-limits`), **derived server-side** and served
     * ready-made so no client ever holds a copy of the retention constant or the anchor rule.
     *
     * All four are **required and non-null**. They are always present on a `200`: the backend rejects a
     * non-canonical `startsAt`/`endsAt` on create, stamps the limit fields at mint, and serves an
     * incomplete marker as `gone` (→ 404) rather than a partial `200`. A `200` lacking any is therefore
     * malformed / transient → [Failed], never a [Found] with an invented one — [startsAt] is a **floor**
     * and [endsAt] a **ceiling** on this membership's capture-date range (capability
     * `photo-selection-policy`), and a client that defaulted either would silently move that bound;
     * [deletesAt] is a witness the self-leave depends on (capability `leave-event`), and an invented one
     * would decide whether a membership is destroyed. Failing loudly and retrying is the only safe
     * reading.
     */
    data class Found(
        val name: String,
        val startsAt: String,
        val endsAt: String,
        val deletesAt: String,
    ) : EventDetails
    data object NotFound : EventDetails
    data object Failed : EventDetails
}

/**
 * Fetches an event's details by id for the join confirmation gate. Non-throwing: a transport/parse
 * error maps to [EventDetails.Failed], never an exception (the gate reduces it to a retryable state).
 */
interface EventDirectory {
    suspend fun fetch(eventId: String): EventDetails
}
