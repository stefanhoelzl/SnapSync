package app.snapsync.ports

import app.snapsync.model.RenameOutcome

/**
 * The network seam for renaming an event (capability `manage-membership`). Non-throwing: a transport or parse
 * error maps to [RenameOutcome.Transient], never an exception.
 *
 * [name] arrives **already trimmed** by the caller, matching [EventCreation]'s contract; the backend
 * trims again and its echo is authoritative.
 */
interface EventRename {
    suspend fun rename(eventId: String, name: String): RenameOutcome
}
