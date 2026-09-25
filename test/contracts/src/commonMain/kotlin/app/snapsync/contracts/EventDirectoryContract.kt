package app.snapsync.contracts

import app.snapsync.ports.EventDetails
import app.snapsync.ports.EventDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The backend states an [EventDirectory] clause needs. */
enum class EventDirectoryState {
    /** An event the edge created, fetched by a build it serves. */
    EVENT_EXISTS,

    /** An id no event was ever created under. */
    NO_SUCH_EVENT,

    /** An event that exists, fetched by a build older than the edge's minimum. */
    VERSION_REFUSED,

    /** An event that exists, fetched with a credential the edge never issued. */
    FOREIGN_TOKEN,
}

/**
 * What the event directory promises (`docs/architecture.md` — this list IS the specification of the
 * port's obligations): the details the join gate and the status screen read, and how the edge's gate treats
 * this read. The version gate precedes every route, so an obsolete build is refused here, at the first call a
 * joining device makes. The CREDENTIAL gate does not apply: the event read is public by design (capability
 * `event-site` — the event id is the read capability), so a dead credential neither blocks the join
 * gate's fetch nor starts recovery from it. The rejected-credential clause is on a gated route: `EventJoin`.
 */
object EventDirectoryContract : Contract<EventDirectoryState, EdgeSubject<EventDirectory>>("EventDirectory") {

    /** Enters [state] on the edge [setup] drives. Bindings call exactly this. */
    suspend fun seed(state: EventDirectoryState, clauseId: String, setup: EdgeSetup): Seeded {
        if (state == EventDirectoryState.NO_SUCH_EVENT) return Seeded(eventId = setup.freshId(), deviceId = setup.freshId())
        val event = setup.createEvent("directory $clauseId")
        val identity = when (state) {
            EventDirectoryState.VERSION_REFUSED -> ClientIdentity(REFUSED_APP_VERSION, token = null)
            EventDirectoryState.FOREIGN_TOKEN -> ClientIdentity(SERVED_APP_VERSION, FOREIGN_TOKEN)
            else -> ClientIdentity.SERVED
        }
        return Seeded(event.eventId, setup.freshId(), event, identity)
    }

    override val clauses = clauses {

        clause("EXISTING_EVENT_IS_FOUND_WITH_ITS_WINDOW", EventDirectoryState.EVENT_EXISTS) { s ->
            val found = assertIs<EventDetails.Found>(s.port.fetch(s.seeded.eventId))
            assertEquals(s.seeded.event!!.name, found.name)
            assertEquals(SEEDED_STARTS_AT, found.startsAt.at.iso, "the start the creator chose")
            assertEquals(SEEDED_ENDS_AT, found.endsAt.at.iso, "the end the creator chose")
            // The lifetime is 30 days from max(createdAt, startsAt); the seeded start is in the future.
            assertEquals("2030-01-31T00:00:00Z", found.deletesAt.at.iso, "deletesAt is startsAt + 30 days")
        }

        clause("UNKNOWN_EVENT_IS_NOT_FOUND", EventDirectoryState.NO_SUCH_EVENT) { s ->
            assertEquals(EventDetails.NotFound, s.port.fetch(s.seeded.eventId), "absent is NotFound, never Failed")
        }

        clause("REFUSED_BUILD_LEARNS_THE_MINIMUM", EventDirectoryState.VERSION_REFUSED) { s ->
            assertIsNot<EventDetails.Found>(s.port.fetch(s.seeded.eventId), "a refused build is served nothing")
            assertTrue(s.gate.buildRefused, "the refusal reaches the app")
            val minimum = assertNotNull(s.gate.refusedMinimum, "the edge names the minimum and the client reads it")
            assertTrue(Regex("""\d+\.\d+""").matches(minimum), "the minimum is an X.Y marketing version: $minimum")
            assertFalse(s.gate.credentialRejected, "a refused build is not a rejected credential")
        }

        clause("THE_READ_IS_PUBLIC_WHATEVER_THE_CREDENTIAL", EventDirectoryState.FOREIGN_TOKEN) { s ->
            assertIs<EventDetails.Found>(s.port.fetch(s.seeded.eventId), "the event read is authorized by the id alone")
            assertFalse(s.gate.credentialRejected, "a public read never starts credential recovery")
        }
    }
}
