package app.snapsync.ports

/** The outcome of a `POST /events` create call — a closed set the use-case maps to [CreationStatus]. */
sealed interface CreateOutcome {
    /**
     * `201` — the server minted the event; [eventId] is the canonical UUID to provision and [name]
     * is the stored event name (carried straight into `EventConfig`, so create needs no metadata fetch).
     */
    data class Created(val eventId: String, val name: String? = null) : CreateOutcome

    /** `400` — the server rejected the name. */
    data object InvalidName : CreateOutcome

    /** Any other non-2xx, transport, or parse failure. */
    data object Transient : CreateOutcome
}

/**
 * The network seam for minting an event. [endsAt] is the host's chosen event-window end (a canonical
 * `…Z` string); it is **nullable** because the backend treats an absent `endsAt` as "use the legacy
 * `startsAt + 30d`" (capability `event-limits`). The interactive create always supplies one (the create
 * screen requires a range); the headless dev trigger may omit it.
 */
interface EventCreation {
    suspend fun create(name: String, startsAt: String, endsAt: String?): CreateOutcome
}
