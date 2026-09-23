package app.snapsync.contracts

import app.snapsync.ports.LeaveNotifier
import kotlin.test.assertTrue

/** The backend states a [LeaveNotifier] clause needs. The port speaks for [Seeded.deviceId]. */
enum class LeaveNotifierState {
    /** An event the device has joined. */
    MEMBER,

    /** An id no event was ever created under. */
    NO_SUCH_EVENT,
}

/** What telling the edge this device is leaving promises (capability `port-contracts` — the port's specification). */
object LeaveNotifierContract : Contract<LeaveNotifierState, EdgeSubject<LeaveNotifier>>("LeaveNotifier") {

    suspend fun seed(state: LeaveNotifierState, clauseId: String, setup: EdgeSetup): Seeded {
        if (state == LeaveNotifierState.NO_SUCH_EVENT) return Seeded(eventId = setup.freshId(), deviceId = setup.freshId())
        val event = setup.createEvent("leave $clauseId")
        val device = setup.freshId()
        setup.join(event.eventId, device)
        return Seeded(event.eventId, device, event)
    }

    override val clauses = clauses {

        clause("A_MEMBER_LEAVES", LeaveNotifierState.MEMBER) { s ->
            assertTrue(s.port.notifyLeaving(s.seeded.eventId).isSuccess)
        }

        clause("LEAVING_AN_UNKNOWN_EVENT_FAILS", LeaveNotifierState.NO_SUCH_EVENT) { s ->
            assertTrue(s.port.notifyLeaving(s.seeded.eventId).isFailure, "the edge says the event is gone")
        }
    }
}
