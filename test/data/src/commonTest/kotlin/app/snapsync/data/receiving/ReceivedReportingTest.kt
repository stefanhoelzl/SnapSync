package app.snapsync.data.receiving

import app.snapsync.application.membership.FixedIdentity
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.ok
import app.snapsync.data.backend.text
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.ports.HttpResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * How far this device has received an event, told to the backend (`reportReceived`, capability `event-lifetime`): for
 * the device its identity names — none before the first unlock — and the positions recorded remembered in this process
 * alone.
 */
class ReceivedReportingTest {
    private var answer: HttpResult = ok()
    private val transport = ScriptedTransport { answer }
    private val identity = FixedIdentity(DeviceIdRead.Ok(ME, DeviceIdRead.Via.READ))
    private val reporting = ReceivedReporting(
        BackendGateway(exchangeOver(transport), ScriptedCredential("T1")),
        identity,
    )

    @Test
    fun `a position the backend records is recorded, for this device`() = runTest {
        assertSame(ReceivedReply.Recorded, reporting.report(EVENT, 12))

        val request = transport.requests.single()
        assertEquals(true, request.url.contains(EVENT.value) && request.url.contains(ME.value), request.url)
        assertEquals(true, request.body?.contains("12") == true, "${request.body}")
    }

    @Test
    fun `a refused or unanswered report is not recorded, and nothing is sent without this device's id`() = runTest {
        for (refusal in listOf(text(409, "not a member"), text(502, "down"), HttpResult.Unreachable("offline"))) {
            answer = refusal
            assertIs<ReceivedReply.NotRecorded>(reporting.report(EVENT, 12), "$refusal")
        }
        transport.requests.clear()
        for (read in listOf(DeviceIdRead.Absent, DeviceIdRead.Unavailable("locked"))) {
            identity.read = read
            assertIs<ReceivedReply.NotRecorded>(reporting.report(EVENT, 12))
        }
        assertEquals(emptyList(), transport.requests)
    }

    @Test
    fun `the positions recorded and the recognition passes run are this process's memory`() {
        val reported = ReportedPositions()
        assertNull(reported.last(EVENT))
        reported.recorded(EVENT, 4)
        reported.recorded(EVENT, 9)
        assertEquals(9L, reported.last(EVENT))
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
