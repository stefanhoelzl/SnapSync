package app.snapsync.contracts

import app.snapsync.ports.EventJoin
import app.snapsync.model.JoinResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The backend states an [EventJoin] clause needs. */
enum class EventJoinState {
    /** An event with room, and a device that has not joined it. */
    EVENT_OPEN,

    /** An event every seat of which is taken. */
    EVENT_FULL,

    /** An id no event was ever created under. */
    NO_SUCH_EVENT,

    /** An open event, joined with a credential the edge never issued. */
    FOREIGN_TOKEN,
}

/**
 * What joining promises (`docs/architecture.md` — this list IS the port's specification). Joining is the
 * ONLY route that decides membership and capacity, and it is gated, so it is also where a dead credential is
 * first refused and credential recovery starts.
 */
object EventJoinContract : Contract<EventJoinState, EdgeSubject<EventJoin>>("EventJoin") {

    suspend fun seed(state: EventJoinState, clauseId: String, setup: EdgeSetup): Seeded {
        if (state == EventJoinState.NO_SUCH_EVENT) return Seeded(eventId = setup.freshId(), deviceId = setup.freshId())
        val event = setup.createEvent("join $clauseId")
        if (state == EventJoinState.EVENT_FULL) setup.fillToCapacity(event.eventId)
        val identity = if (state == EventJoinState.FOREIGN_TOKEN) ClientIdentity(SERVED_APP_VERSION, FOREIGN_TOKEN) else ClientIdentity.SERVED
        return Seeded(event.eventId, setup.freshId(), event, identity)
    }

    override val clauses = clauses {

        clause("AN_OPEN_EVENT_IS_JOINED", EventJoinState.EVENT_OPEN) { s ->
            assertEquals(JoinResult.JOINED, s.port.join(s.seeded.eventId, s.seeded.deviceId))
        }

        clause("A_REJOIN_IS_JOINED", EventJoinState.EVENT_OPEN) { s ->
            s.port.join(s.seeded.eventId, s.seeded.deviceId)
            assertEquals(JoinResult.JOINED, s.port.join(s.seeded.eventId, s.seeded.deviceId), "joining is idempotent")
        }

        clause("A_FULL_EVENT_IS_FULL", EventJoinState.EVENT_FULL) { s ->
            assertEquals(JoinResult.EVENT_FULL, s.port.join(s.seeded.eventId, s.seeded.deviceId), "full, never not-found")
        }

        clause("AN_UNKNOWN_EVENT_IS_NOT_FOUND", EventJoinState.NO_SUCH_EVENT) { s ->
            assertEquals(JoinResult.EVENT_NOT_FOUND, s.port.join(s.seeded.eventId, s.seeded.deviceId))
        }

        clause("A_FOREIGN_TOKEN_IS_REJECTED", EventJoinState.FOREIGN_TOKEN) { s ->
            assertEquals(JoinResult.FAILED, s.port.join(s.seeded.eventId, s.seeded.deviceId), "a refused credential joins nothing")
            assertTrue(s.gate.credentialRejected, "the rejection reaches the app, which starts credential recovery")
            assertFalse(s.gate.buildRefused, "a rejected credential is not a refused build")
        }
    }
}
