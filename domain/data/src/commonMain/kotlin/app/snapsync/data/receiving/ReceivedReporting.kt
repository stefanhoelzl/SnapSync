package app.snapsync.data.receiving

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.domain.event.EventId
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.ReportReceivedReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerIssue
import app.snapsync.wire.models.Received

/**
 * The backend's record of how far this device has received an event (`reportReceived`): the union position up to which
 * it holds every photo of the others. Read per call for the device [identity] names: before the first unlock it cannot
 * be. The backend keeps the highest position it was told.
 */
class ReceivedReporting(private val gateway: BackendGateway, private val identity: DeviceIdentity) {
    suspend fun report(event: EventId, position: Long): ReceivedReply = when (val me = identity.id()) {
        is DeviceIdRead.Ok -> replied(
            gateway.send(Routes.ReportReceived.request(event.value, me.value.value, Received(position))),
        )
        DeviceIdRead.Absent, is DeviceIdRead.Unavailable -> ReceivedReply.NotRecorded("this device's id: $me")
    }

    private fun replied(reply: ReportReceivedReply): ReceivedReply = when (reply) {
        ReportReceivedReply.Recorded -> ReceivedReply.Recorded
        is ClientError, is ServerIssue, is NoAnswer -> ReceivedReply.NotRecorded("$reply")
    }
}

/** What the backend did with a received position. */
sealed interface ReceivedReply {
    data object Recorded : ReceivedReply

    class NotRecorded(val detail: String) : ReceivedReply
}
