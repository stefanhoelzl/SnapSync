package app.snapsync.data.membership

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.json
import app.snapsync.data.backend.ok
import app.snapsync.data.backend.text
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.domain.service.ServiceFault
import app.snapsync.ports.HttpResult
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DEVICE_ID = "11111111-0000-4000-8000-000000000002"

/** An enrolment and a leave, as the server answers them. */
class EnrolmentTest {

    private val transport = ScriptedTransport()
    private val gateway = BackendGateway(exchangeOver(transport), ScriptedCredential("T1"))

    private fun answering(result: HttpResult) {
        transport.answer = { result }
    }

    @Test
    fun `an enrolment is admitted, refused for an event over, unverified, or failed`() = runTest {
        val join = EventJoin(gateway)
        val cases = listOf(
            ok(200) to EnrollFact.Admitted,
            json(410, """{"error":"closed"}""") to EnrollFact.EventOver,
            text(401, "unattested") to EnrollFact.Unverified,
            text(404, "event not found") to EnrollFact.Failed(fault = null),
            text(502, "down") to EnrollFact.Failed(ServiceFault.TROUBLE),
            json(503, """{"error":"maintenance"}""", mapOf("Retry-After" to "60")) to EnrollFact.Failed(ServiceFault.UPDATING),
            HttpResult.Unreachable("offline") to EnrollFact.Failed(ServiceFault.UNREACHABLE),
            // An answer from a working service the app has no reading of — this build too old — is the service not
            // reached, never its failure.
            json(426, """{"error":"app too old","minAppVersion":"9.9"}""") to EnrollFact.Failed(ServiceFault.UNREACHABLE),
        )
        for ((answer, fact) in cases) {
            answering(answer)
            assertEquals(fact, join.enroll(testEventId(EVENT), testDeviceId(DEVICE_ID)), "$answer")
        }
        assertTrue(transport.calls.all { it.startsWith("PUT /events/$EVENT/devices/$DEVICE_ID") }, "${transport.calls}")
    }

    private class Identity(var read: DeviceIdRead) : DeviceIdentity {
        override fun id(): DeviceIdRead = read
    }

    @Test
    fun `a leave is confirmed when the server recorded it or holds nothing to leave`() = runTest {
        val notifier = LeaveNotifier(gateway, Identity(DeviceIdRead.Ok(testDeviceId(DEVICE_ID), DeviceIdRead.Via.READ)))
        for ((answer, fact) in listOf(
            ok(200) to LeaveFact.Confirmed,
            ok(404) to LeaveFact.Confirmed,
            text(400, "x") to LeaveFact.Unanswered,
            text(502, "x") to LeaveFact.Unanswered,
            HttpResult.Unreachable("offline") to LeaveFact.Unanswered,
        )) {
            answering(answer)
            assertEquals(fact, notifier.leave(testEventId(EVENT)), "$answer")
        }
        assertEquals("DELETE /events/$EVENT/devices/$DEVICE_ID T1", transport.calls.first(), "no flag rides with it")
    }

    @Test
    fun `a leave this device cannot name itself for waits`() = runTest {
        for (read in listOf(DeviceIdRead.Absent, DeviceIdRead.Unavailable("locked"))) {
            assertEquals(LeaveFact.Unanswered, LeaveNotifier(gateway, Identity(read)).leave(testEventId(EVENT)))
        }
        assertEquals(emptyList(), transport.calls, "nothing was sent")
    }
}
