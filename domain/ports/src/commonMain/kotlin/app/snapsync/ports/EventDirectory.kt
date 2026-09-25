package app.snapsync.ports

import app.snapsync.model.EventLookup

/**
 * Fetches an event's details by id for the join confirmation gate. Non-throwing: a transport/parse
 * error maps to [EventLookup.Failed], never an exception (the gate reduces it to a retryable state).
 */
interface EventDirectory {
    suspend fun fetch(eventId: String): EventLookup
}
