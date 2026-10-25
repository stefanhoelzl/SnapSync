package app.snapsync.contracts

import app.snapsync.ports.EntryEvent
import app.snapsync.ports.EntryEvents
import app.snapsync.ports.EntryOutcome
import kotlin.time.Duration.Companion.milliseconds

/** The one state an entry-event sink has: told nothing yet. */
enum class EntryEventsState {
    FRESH,
}

/**
 * What an entry-event sink promises (`docs/patterns.md`, "Entry events"): it takes every event the runtime tells and
 * returns — it is told from inside the runtime's bookkeeping, where a throw would end the run it describes.
 */
object EntryEventsContract : Contract<EntryEventsState, EntryEvents>("EntryEvents") {

    override val clauses = clauses {

        clause(
            "FRESH_EVERY_EVENT_IS_TAKEN",
            EntryEventsState.FRESH,
            covers = cells { on<EntryEvents>().answers(EntryEvents::tell).returns() },
        ) { events ->
            events.tell(EntryEvent.Delivered("OnSilentPush", run = 1))
            events.tell(EntryEvent.Answered("OnSilentPush", run = 1, after = 40.milliseconds))
            events.tell(EntryEvent.Done("OnSilentPush", run = 1, after = 3200.milliseconds, EntryOutcome.COMPLETED))
            events.tell(EntryEvent.Done("OnArm", run = 2, after = 10.milliseconds, EntryOutcome.FAILED))
        }
    }
}
