package app.snapsync.model

/** The outcome of a `POST /events` create call — a closed set the use-case maps to [CreationStatus]. */
sealed interface CreateOutcome {
    /**
     * `201` — the server minted the event; [eventId] is the canonical UUID to provision and [name]
     * is the stored event name (carried straight into `EventConfig`, so create needs no metadata fetch).
     */
    data class Created(val eventId: String, val name: String? = null) : CreateOutcome

    /** `400` — the server rejected the name. */
    data object InvalidName : CreateOutcome

    /**
     * `400` — the server rejected the date range: an end not after the start, or a window longer than the
     * deployment's maximum. The create screen's picker cannot produce either, so this arrives only when the
     * backend's limit moved after this build was made — and then it must not read as a refused name.
     */
    data object InvalidWindow : CreateOutcome

    /** Any other non-2xx, transport, or parse failure. */
    data object Transient : CreateOutcome
}
