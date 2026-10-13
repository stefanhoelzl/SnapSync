package app.snapsync.model

import app.snapsync.domain.event.OpenUntil

/**
 * The command port for creating an event: fire-and-forget, like `SystemUi.openSettings`. It returns
 * nothing; the outcome arrives exclusively via the creation status read-model
 * (in-flight then either config becoming present, or [CreationStatus.Failed]).
 *
 * [startsAt] is the event's start date — the host's statement of when the event began. It arrives here
 * **already canonical** (`yyyy-MM-dd'T'HH:mm:ss'Z'`), converted from the user's local pick by the caller, so this
 * capability needs no clock, no timezone, and no dependency on the cutoff codec. [openUntil] is the day guests may join
 * until, chosen for an event created after its end; `null` for every other event.
 */
interface EventCreator {
    suspend fun create(name: String, startsAt: String, endsAt: String, openUntil: OpenUntil?)
}
