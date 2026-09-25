package app.snapsync.contracts

import app.snapsync.model.CreateOutcome
import app.snapsync.ports.EventCreation
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The backend states an [EventCreation] clause needs. The edge mints the event, so there is only one. */
enum class EventCreationState {
    /** An edge serving this build. */
    SERVING,
}

/**
 * What creating an event promises (`docs/architecture.md` — this list IS the specification of the port's
 * obligations). The port tells a refused NAME from a refused DATE RANGE, so each refusal clause asserts both
 * that the edge refuses and which of the two it said.
 */
object EventCreationContract : Contract<EventCreationState, EdgeSubject<EventCreation>>("EventCreation") {

    @Suppress("UNUSED_PARAMETER")
    suspend fun seed(state: EventCreationState, clauseId: String, setup: EdgeSetup): Seeded =
        Seeded(eventId = setup.freshId(), deviceId = setup.freshId())

    private val UUID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

    override val clauses = clauses {

        clause("A_VALID_EVENT_IS_CREATED", EventCreationState.SERVING) { s ->
            val created = assertIs<CreateOutcome.Created>(s.port.create("Anna's Birthday", SEEDED_STARTS_AT, SEEDED_ENDS_AT))
            assertTrue(UUID.matches(created.eventId), "the edge mints a UUID event id: ${created.eventId}")
            assertEquals("Anna's Birthday", created.name, "the edge echoes the name it stored")
        }

        clause("AN_ABSENT_END_IS_ACCEPTED", EventCreationState.SERVING) { s ->
            assertIs<CreateOutcome.Created>(s.port.create("No end", SEEDED_STARTS_AT, null), "the edge supplies the end")
        }

        clause("A_BLANK_NAME_IS_REFUSED", EventCreationState.SERVING) { s ->
            assertEquals(CreateOutcome.InvalidName, s.port.create("   ", SEEDED_STARTS_AT, SEEDED_ENDS_AT))
        }

        clause("AN_END_BEFORE_THE_START_IS_REFUSED", EventCreationState.SERVING) { s ->
            assertEquals(CreateOutcome.InvalidWindow, s.port.create("Backwards", SEEDED_ENDS_AT, SEEDED_STARTS_AT))
        }

        clause("A_WINDOW_LONGER_THAN_30_DAYS_IS_REFUSED", EventCreationState.SERVING) { s ->
            assertEquals(CreateOutcome.InvalidWindow, s.port.create("Too long", SEEDED_STARTS_AT, "2030-02-15T00:00:00Z"))
        }
    }
}
