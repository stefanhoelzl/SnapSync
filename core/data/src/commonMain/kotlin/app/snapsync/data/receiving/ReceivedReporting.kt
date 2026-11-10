package app.snapsync.data.receiving

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.identity.AppDeviceIdentity
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.domain.event.EventId
import app.snapsync.domain.receiving.DeliveredPhoto
import app.snapsync.domain.receiving.ReceivedReport
import app.snapsync.domain.time.Timestamp
import app.snapsync.format.TimestampText
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.ReportReceivedReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerIssue
import app.snapsync.wire.models.Delivered
import app.snapsync.wire.models.Received

/**
 * The backend's record of how far this device has received an event (`reportReceived`): the union position up to which
 * it holds every photo of the others, or that it receives nothing at all. Read per call for the device [identity]
 * names: before the first unlock it cannot be. The backend keeps the highest position it was told, and the latest word
 * on receiving nothing. The report also carries the delivery facts of the photos received since the last one it
 * recorded ([DeliveredPhoto]; decision record `changes/delivery-record`, D3).
 */
class ReceivedReporting(private val gateway: BackendGateway, private val identity: AppDeviceIdentity) {
    suspend fun report(
        event: EventId,
        report: ReceivedReport,
        delivered: List<DeliveredPhoto>,
    ): ReceivedReply = when (val me = identity.id()) {
        is DeviceIdRead.Ok -> replied(
            gateway.send(Routes.ReportReceived.request(event.value, me.value.value, wire(report, delivered))),
        )
        is DeviceIdRead.Unavailable -> ReceivedReply.NotRecorded("this device's id: $me")
    }

    /** A device that receives nothing names no position: `0` never moves a recorded one back. */
    private fun wire(report: ReceivedReport, delivered: List<DeliveredPhoto>): Received = when (report) {
        is ReceivedReport.Position -> Received(report.position, delivered = wire(delivered))
        ReceivedReport.ReceivesNothing -> Received(position = 0, receivesNothing = true, delivered = wire(delivered))
    }

    /** None when there is nothing to tell: a report without facts is the one it always was. */
    private fun wire(delivered: List<DeliveredPhoto>): List<Delivered>? = delivered.map {
        Delivered(
            owner = it.ref.sourceDeviceId.value,
            asset = it.ref.sourceAssetId.value,
            downloaded = text(it.downloaded),
            added = TimestampText.format(it.added),
            by = it.by,
        )
    }.takeUnless { it.isEmpty() }

    private fun text(at: Timestamp?): String? = when (at) {
        null -> null
        is Timestamp -> TimestampText.format(at)
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
