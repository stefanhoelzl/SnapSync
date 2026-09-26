package app.snapsync.model

/**
 * The command port for creating an event: fire-and-forget, like `SystemUi.openSettings`. It MUST NOT
 * return a value and MUST NOT suspend; the outcome arrives exclusively via [CreationStatusSource]
 * (in-flight then either config becoming present, or [CreationStatus.Failed]).
 *
 * [startsAt] is the event's start date — the host's statement of when the event began (capability
 * `event-creation`). It arrives here **already canonical** (`yyyy-MM-dd'T'HH:mm:ss'Z'`, capability
 * `photo-sharing`), converted from the user's local pick by the caller, so this capability needs no
 * clock, no timezone, and no dependency on the cutoff codec.
 */
interface EventCreator {
    suspend fun create(name: String, startsAt: String, endsAt: String)
}

/** A no-op [EventCreator] for hosts/tests that forge [CreationStatus] directly (e.g. the harness). */
object NoOpEventCreator : EventCreator {
    override suspend fun create(name: String, startsAt: String, endsAt: String) = Unit
}
