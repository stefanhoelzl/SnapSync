package app.snapsync.data.push

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.UNAUTHORIZED
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.ok
import app.snapsync.data.backend.text
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
    private val publisher = PushEndpointPublisher(BackendGateway(exchangeOver(transport), ScriptedCredential("T1")))

    @Test
    fun `only a recorded write is accepted`() = runTest {
        transport.answer = { ok(201) }
        assertEquals(PushPublishReply.Accepted, publisher.publish(ENDPOINT, testDeviceId(DEVICE)))
        assertTrue(transport.calls.single().startsWith("PUT /devices/$DEVICE T1"), "${transport.calls}")
        assertTrue(transport.requests.single().body.orEmpty().contains("\"token\":\"tok\""))

        for (answer in listOf(UNAUTHORIZED, text(400, "bad"), text(502, "down"), HttpResult.Unreachable("offline"))) {
            transport.answer = { answer }
            assertIs<PushPublishReply.Refused>(publisher.publish(ENDPOINT, testDeviceId(DEVICE)), "$answer")
        }
    }

    private companion object {
        const val DEVICE = "11111111-0000-4000-8000-000000000002"
        val ENDPOINT = PushEndpoint("apns", "tok", "sandbox")
    }
}
