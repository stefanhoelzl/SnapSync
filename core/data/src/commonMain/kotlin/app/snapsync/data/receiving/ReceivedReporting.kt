package app.snapsync.data.receiving

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.identity.AppDeviceIdentity
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.domain.event.EventId
import app.snapsync.domain.receiving.ReceivedReport
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.ReportReceivedReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerIssue
import app.snapsync.wire.models.Received

/**
 * The backend's record of how far this device has received an event (`reportReceived`): the union position up to which
 * it holds every photo of the others, or that it receives nothing at all. Read per call for the device [identity]
 * names: before the first unlock it cannot be. The backend keeps the highest position it was told, and the latest word
 * on receiving nothing.
 */
class ReceivedReporting(private val gateway: BackendGateway, private val identity: AppDeviceIdentity) {
    suspend fun report(event: EventId, report: ReceivedReport): ReceivedReply = when (val me = identity.id()) {
        is DeviceIdRead.Ok -> replied(
            gateway.send(Routes.ReportReceived.request(event.value, me.value.value, wire(report))),
        )
        is DeviceIdRead.Unavailable -> ReceivedReply.NotRecorded("this device's id: $me")
    }

    /** A device that receives nothing names no position: `0` never moves a recorded one back. */
    private fun wire(report: ReceivedReport): Received = when (report) {
        is ReceivedReport.Position -> Received(report.position)
        ReceivedReport.ReceivesNothing -> Received(position = 0, receivesNothing = true)
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
