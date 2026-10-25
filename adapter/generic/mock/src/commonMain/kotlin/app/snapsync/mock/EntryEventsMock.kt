package app.snapsync.mock

import app.snapsync.levers.EntryOutcomeKind
import app.snapsync.levers.WorldEvent
import app.snapsync.ports.EntryEvent
import app.snapsync.ports.EntryEvents
import app.snapsync.ports.EntryOutcome
import kotlin.concurrent.Volatile

/**
 * What the app's runtime tells of its entry points' work, taken where the control channel reads it (`docs/testing.md`,
 * "How a test waits"): each event told to the watcher as it happens — the app's and the extension's alike, as on the
 * JVM both processes are composed over the one device. Nothing to pull, so no operator face.
 */
class EntryEventsMock {
    @Volatile private var watcher: WorldWatcher = WorldWatcher {}

    /** Tells [listener] each entry event as the runtime tells it. */
    fun watch(listener: WorldWatcher) {
        watcher = listener
    }

    /** A process's face. Every process the device composes takes its own; all tell the one watcher. */
    fun port(): EntryEvents = TellingEntryEvents(this)

    internal fun told(event: WorldEvent) = watcher.told(event)
}

/** An [EntryEvents] that tells each event, as the world stream speaks it, to its [mock]'s watcher. */
internal class TellingEntryEvents(private val mock: EntryEventsMock) : EntryEvents {
    override fun tell(event: EntryEvent) = mock.told(
        when (event) {
            is EntryEvent.Delivered -> WorldEvent.Delivered(event.entry, event.run, event.about)
            is EntryEvent.Answered -> WorldEvent.Answered(event.entry, event.run, event.after.inWholeMilliseconds)
            is EntryEvent.Done ->
                WorldEvent.Done(event.entry, event.run, event.after.inWholeMilliseconds, kindOf(event.outcome))
        },
    )
}

private fun kindOf(outcome: EntryOutcome): EntryOutcomeKind = when (outcome) {
    EntryOutcome.COMPLETED -> EntryOutcomeKind.COMPLETED
    EntryOutcome.EXPIRED -> EntryOutcomeKind.EXPIRED
    EntryOutcome.FAILED -> EntryOutcomeKind.FAILED
}
