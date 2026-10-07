package app.snapsync.model

/**
 * The command port for creating an event: fire-and-forget, like `SystemUi.openSettings`. It returns
 * nothing; the outcome arrives exclusively via the creation status read-model
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
