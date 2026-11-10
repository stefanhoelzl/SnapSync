package app.snapsync.data.receiving

import app.snapsync.application.membership.FixedAppIdentity
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.ok
import app.snapsync.data.backend.text
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.DeliveredPhoto
import app.snapsync.domain.receiving.ReceivedReport
import app.snapsync.domain.time.Timestamp
import app.snapsync.ports.HttpResult
import app.snapsync.testids.testDeviceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant

/**
 * How far this device has received an event, told to the backend (`reportReceived`, capability `event-lifetime`): for
 * the device its identity names — none before the first unlock — and the positions recorded remembered in this process
 * alone.
 */
class ReceivedReportingTest {
    private var answer: HttpResult = ok()
    private val transport = ScriptedTransport { answer }
    private val identity = FixedAppIdentity(DeviceIdRead.Ok(ME, DeviceIdRead.Via.READ))
    private val reporting = ReceivedReporting(
        BackendGateway(exchangeOver(transport), ScriptedCredential("T1")),
        identity,
    )

    @Test
    fun `a position the backend records is recorded, for this device`() = runTest {
        assertSame(ReceivedReply.Recorded, reporting.report(EVENT, ReceivedReport.Position(12), emptyList()))

        val request = transport.requests.single()
        assertEquals(true, request.url.contains(EVENT.value) && request.url.contains(ME.value), request.url)
        assertEquals(true, request.body?.contains("12") == true, "${request.body}")
        assertEquals(false, request.body?.contains("receivesNothing") == true, "a position says the device receives")
    }

    @Test
    fun `a report carries the photos received since the last, and none when there are none`() = runTest {
        val photo = DeliveredPhoto(
            AssetRef(testDeviceId("22222222-0000-4000-8000-000000000003"), AssetId.parse("A1")!!),
            Timestamp.of(Instant.parse("2026-07-14T12:00:20Z")),
            Timestamp.of(Instant.parse("2026-07-14T12:00:21Z")),
            "OnDownloadSessionEvents",
        )
        reporting.report(EVENT, ReceivedReport.Position(12), listOf(photo))
        val body = transport.requests.single().body.orEmpty()
        assertEquals(
            true,
            body.contains(
                "\"delivered\":[{\"owner\":\"22222222-0000-4000-8000-000000000003\",\"asset\":\"A1\"," +
                    "\"downloaded\":\"2026-07-14T12:00:20Z\",\"added\":\"2026-07-14T12:00:21Z\",\"by\":\"OnDownloadSessionEvents\"}]",
            ),
            body,
        )
        transport.requests.clear()
        reporting.report(EVENT, ReceivedReport.Position(13), listOf(DeliveredPhoto(photo.ref, null, photo.added, null)))
        assertEquals(false, transport.requests.single().body.orEmpty().contains("downloaded"), "a photo staged before")
        transport.requests.clear()
        reporting.report(EVENT, ReceivedReport.Position(14), emptyList())
        assertEquals(false, transport.requests.single().body.orEmpty().contains("delivered"))
    }

    @Test
    fun `a device that receives nothing says so, naming no position`() = runTest {
        assertSame(ReceivedReply.Recorded, reporting.report(EVENT, ReceivedReport.ReceivesNothing, emptyList()))
        val body = transport.requests.single().body.orEmpty()
        assertEquals(true, body.contains("\"receivesNothing\":true") && body.contains("\"position\":0"), body)
    }

    @Test
    fun `a refused or unanswered report is not recorded, and nothing is sent without this device's id`() = runTest {
        for (refusal in listOf(text(409, "not a member"), text(502, "down"), HttpResult.Unreachable("offline"))) {
            answer = refusal
            assertIs<ReceivedReply.NotRecorded>(
                reporting.report(EVENT, ReceivedReport.Position(12), emptyList()),
                "$refusal",
            )
        }
        transport.requests.clear()
        for (read in listOf(DeviceIdRead.Unavailable("locked"))) {
            identity.read = read
            assertIs<ReceivedReply.NotRecorded>(reporting.report(EVENT, ReceivedReport.Position(12), emptyList()))
        }
        assertEquals(emptyList(), transport.requests)
    }

    @Test
    fun `the reports recorded and the recognition passes run are this process's memory`() {
        val reported = ReportedReceived()
        assertNull(reported.last(EVENT))
        reported.recorded(EVENT, ReceivedReport.Position(4))
        reported.recorded(EVENT, ReceivedReport.ReceivesNothing)
        assertEquals(ReceivedReport.ReceivesNothing, reported.last(EVENT))
        assertNull(reported.last(OTHER_EVENT))

        val passes = RecognitionPasses()
        assertEquals(false, passes.ranFor(EVENT))
        passes.ran(EVENT)
        assertEquals(true, passes.ranFor(EVENT))
        assertEquals(false, passes.ranFor(OTHER_EVENT))
        passes.forget()
        assertEquals(false, passes.ranFor(EVENT))
    }
}
