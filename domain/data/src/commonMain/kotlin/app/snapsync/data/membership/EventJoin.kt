package app.snapsync.data.membership

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.faultOf
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.service.ServiceFault
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.Closed
import app.snapsync.wire.api.EventNotFound
import app.snapsync.wire.api.JoinEventReply
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerIssue
import app.snapsync.wire.api.Unauthorized

/**
 * Enrols a device in an event (`joinEvent`): creates or reactivates its membership on the server, and nothing else —
 * no manifest, so a rejoining device's contribution survives untouched. The request a member's join cannot send
 * token-less, so it obtains a credential first.
 */
class EventJoin(private val gateway: BackendGateway) {

    /** Enrol [device] in [event]. */
    suspend fun enroll(event: EventId, device: DeviceId): EnrollFact =
        when (val reply = gateway.sendObtainingFirst(Routes.JoinEvent.request(event.value, device.value))) {
            JoinEventReply.Joined -> EnrollFact.Admitted
            // An event whose photos are deleted admits nobody.
            is Closed -> EnrollFact.EventOver
            // Still refused after the recovery: no credential the server accepts.
            Unauthorized -> EnrollFact.Unverified
            // The event is gone: an answer from a working service.
            EventNotFound -> EnrollFact.Failed(fault = null)
            is ClientError, is ServerIssue, is NoAnswer -> EnrollFact.Failed(faultOf(reply) ?: ServiceFault.UNREACHABLE)
        }
}

/** What the server answered an enrolment. */
sealed interface EnrollFact {
    data object Admitted : EnrollFact

    /** The event admits nobody any more. */
    data object EventOver : EnrollFact

    /** The server accepted no credential of this device's. */
    data object Unverified : EnrollFact

    /**
     * Not admitted: the event is gone, or no answer could be had. [fault] is how the service failed — `null` for an
     * answer from a working one.
     */
    data class Failed(val fault: ServiceFault?) : EnrollFact
}
