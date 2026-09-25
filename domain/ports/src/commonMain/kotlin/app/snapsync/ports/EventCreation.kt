package app.snapsync.ports

import app.snapsync.model.CreateOutcome

/**
 * The network seam for minting an event. [endsAt] is the host's chosen event-window end (a canonical
 * `…Z` string); it is **nullable** because the backend treats an absent `endsAt` as "use the legacy
 * `startsAt + 30d`" (capability `event-lifetime`). The interactive create always supplies one (the create
 * screen requires a range); the headless dev trigger may omit it.
 */
interface EventCreation {
    suspend fun create(name: String, startsAt: String, endsAt: String?): CreateOutcome
}
