package app.snapsync.entry

import app.snapsync.ports.EntryEvent
import app.snapsync.ports.EntryEvents

/**
 * The [EntryEvents] of a process nothing observes from inside — every shipped build, and the upload extension on a
 * device: each event is taken and none is kept. The runtime's log line beside it is the diagnostic.
 */
object NoEntryEvents : EntryEvents {
    override fun tell(event: EntryEvent) = Unit
}
