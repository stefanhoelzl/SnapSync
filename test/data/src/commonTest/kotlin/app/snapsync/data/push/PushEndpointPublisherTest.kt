package app.snapsync.data.push

import app.snapsync.application.membership.FixedIdentity
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.UNAUTHORIZED
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.ok
import app.snapsync.data.backend.text
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.ports.HttpResult
import app.snapsync.testids.testDeviceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A registration as the backend answers it: recorded, or anything else — never a throw. */
class PushEndpointPublisherTest {

    private val transport = ScriptedTransport()
    private val identity = FixedIdentity(DeviceIdRead.Ok(testDeviceId(DEVICE), DeviceIdRead.Via.READ))
    private val publisher =
        PushEndpointPublisher(BackendGateway(exchangeOver(transport), ScriptedCredential("T1")), identity)

    @Test
    fun `only a recorded write is accepted`() = runTest {
        transport.answer = { ok(201) }
        assertEquals(PushPublishReply.Accepted, publisher.publish(ENDPOINT))
        assertTrue(transport.calls.single().startsWith("PUT /devices/$DEVICE T1"), "${transport.calls}")
        assertTrue(transport.requests.single().body.orEmpty().contains("\"token\":\"tok\""))

        for (answer in listOf(UNAUTHORIZED, text(400, "bad"), text(502, "down"), HttpResult.Unreachable("offline"))) {
            transport.answer = { answer }
            assertIs<PushPublishReply.Refused>(publisher.publish(ENDPOINT), "$answer")
        }
    }

    @Test
    fun `a device id that cannot be read sends nothing`() = runTest {
        identity.read = DeviceIdRead.Unavailable("locked")
        assertEquals(PushPublishReply.NoIdentity, publisher.publish(ENDPOINT))
        identity.read = DeviceIdRead.Absent
        assertEquals(PushPublishReply.NoIdentity, publisher.publish(ENDPOINT))
        assertTrue(transport.requests.isEmpty())
    }

    private companion object {
        const val DEVICE = "11111111-0000-4000-8000-000000000002"
        val ENDPOINT = PushEndpoint("apns", "tok", "sandbox")
    }
}
