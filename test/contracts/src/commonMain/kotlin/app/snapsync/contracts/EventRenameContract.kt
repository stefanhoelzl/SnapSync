package app.snapsync.contracts

import app.snapsync.ports.EventRename
import app.snapsync.ports.RenameOutcome
import kotlin.test.assertEquals
import kotlin.test.assertIsNot

/** The backend states an [EventRename] clause needs. */
enum class EventRenameState {
    /** An event the edge created. */
    EVENT_EXISTS,

    /** An id no event was ever created under. */
    NO_SUCH_EVENT,
}

/** What renaming an event promises (`docs/architecture.md` — this list IS the port's specification). */
object EventRenameContract : Contract<EventRenameState, EdgeSubject<EventRename>>("EventRename") {

    suspend fun seed(state: EventRenameState, clauseId: String, setup: EdgeSetup): Seeded = when (state) {
        EventRenameState.NO_SUCH_EVENT -> Seeded(eventId = setup.freshId(), deviceId = setup.freshId())
        EventRenameState.EVENT_EXISTS -> setup.createEvent("rename $clauseId").let { Seeded(it.eventId, setup.freshId(), it) }
    }

    override val clauses = clauses {

        clause("A_RENAME_ANSWERS_THE_NEW_NAME", EventRenameState.EVENT_EXISTS) { s ->
            assertEquals(RenameOutcome.Renamed("Renamed"), s.port.rename(s.seeded.eventId, "Renamed"))
        }

        clause("A_BLANK_NAME_IS_REFUSED", EventRenameState.EVENT_EXISTS) { s ->
            assertEquals(RenameOutcome.InvalidName, s.port.rename(s.seeded.eventId, "   "))
        }

        clause("AN_UNKNOWN_EVENT_IS_NOT_RENAMED", EventRenameState.NO_SUCH_EVENT) { s ->
            assertIsNot<RenameOutcome.Renamed>(s.port.rename(s.seeded.eventId, "Nobody"))
        }
    }
}
