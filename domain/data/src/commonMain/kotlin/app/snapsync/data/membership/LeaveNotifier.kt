package app.snapsync.data.membership

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.EventNotFound
import app.snapsync.wire.api.LeaveEventReply
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerIssue

/**
 * Tells an event that **this device** left it (`leaveEvent`): the server records the membership as gone and keeps what
 * it shared. This device, always — no device crosses it, so it can never make another device leave. The identity is
 * read at each call: before the first unlock it cannot be, and the leave then waits for a later wake.
 */
class LeaveNotifier(private val gateway: BackendGateway, private val identity: DeviceIdentity) {

    /** Tell [event] that this device left it. */
    suspend fun leave(event: EventId): LeaveFact = when (val id = identity.id()) {
        is DeviceIdRead.Ok -> sent(event, id.value)
        DeviceIdRead.Absent, is DeviceIdRead.Unavailable -> LeaveFact.Unanswered
    }

    private suspend fun sent(event: EventId, device: DeviceId): LeaveFact =
        when (gateway.send(Routes.LeaveEvent.request(event.value, device.value))) {
            LeaveEventReply.Left -> LeaveFact.Confirmed
            // An event the server no longer holds has nothing left to leave: the leave is as done as it will be.
            EventNotFound -> LeaveFact.Confirmed
            is ClientError, is ServerIssue, is NoAnswer -> LeaveFact.Unanswered
        }
}

/** What the server answered a leave. */
sealed interface LeaveFact {
    /** The leave is recorded, or there is nothing left to leave. */
    data object Confirmed : LeaveFact

    /** Not confirmed: the next wake sends it again. */
    data object Unanswered : LeaveFact
}
