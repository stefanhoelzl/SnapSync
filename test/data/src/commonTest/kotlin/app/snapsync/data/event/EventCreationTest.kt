package app.snapsync.data.event

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.UNAUTHORIZED
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.json
import app.snapsync.data.backend.text
import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.request.CreateRequest
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.service.ServiceFault
import app.snapsync.format.TimestampText
import app.snapsync.ports.HttpResult
import app.snapsync.testids.testEventId
import app.snapsync.wire.models.EventCreate
import app.snapsync.wire.runtime.WireJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Minting an event (`createEvent`): what is sent, and every answer read as what the create came to. */
class EventCreationTest {

    private val transport = ScriptedTransport()
    private val creation = EventCreation(BackendGateway(exchangeOver(transport), ScriptedCredential("T1")))

    private val start = EventStart(TimestampText.parse("2026-07-14T18:00:00Z"))
    private val end = EventEnd(TimestampText.parse("2026-07-21T18:00:00Z"))

    private fun event(id: String = testEventId("E1").value) =
        """{"eventId":"$id","name":"Party","createdAt":"2026-06-01T00:00:00Z","startsAt":"2026-07-01T00:00:00Z",""" +
            """"endsAt":"2026-07-08T00:00:00Z","openUntil":"2026-07-08T00:00:00Z","capacity":40,""" +
            """"deletesAt":"2026-07-31T00:00:00Z","closedAt":null,"completedAt":null}"""

    private suspend fun answered(reply: HttpResult, openUntil: OpenUntil? = null): CreateResult {
        transport.answer = { reply }
        val request = CreateRequest(RequestId(1), "  Party ", start, end, openUntil, zone = "Europe/Berlin")
        return creation.create(request, key = null)
    }

    private fun sent(): EventCreate = WireJson.decodeFromString(
        EventCreate.serializer(),
        transport.requests.last().body!!,
    )

    @Test
    fun `a served create answers the minted id - a plain event's with no key`() = runTest {
        val created = assertIs<CreateResult.Created>(answered(json(201, event())))
        assertEquals(testEventId("E1"), created.eventId)
        assertNull(created.key)
    }

    @Test
    fun `the name, the range, the open-until and the zone are sent as they were chosen`() = runTest {
        answered(json(201, event()), openUntil = OpenUntil(TimestampText.parse("2026-07-28T18:00:00Z")))
        val sent = sent()
        assertEquals("Party", sent.name, "the name is sent trimmed, as the server keeps it")
        assertEquals("2026-07-14T18:00:00Z" to "2026-07-21T18:00:00Z", sent.startsAt to sent.endsAt)
        assertEquals("2026-07-28T18:00:00Z", sent.openUntil)
        assertEquals("Europe/Berlin", sent.zone)
        assertNull(sent.keyId, "a plain event names no key")
    }

    @Test
    fun `a created event whose id is no event id is a reply the backend could not have meant`() = runTest {
        assertEquals(CreateResult.Failed(ServiceFault.UNREACHABLE), answered(json(201, event(id = "E1"))))
    }

    @Test
    fun `a refused create is the name unless it names the window`() = runTest {
        for (body in listOf("invalid name", "invalid body", "invalid keyId")) {
            assertEquals(CreateResult.InvalidName, answered(text(400, body)), body)
        }
        for (body in listOf("invalid endsAt", "invalid startsAt")) {
            assertEquals(CreateResult.InvalidWindow, answered(text(400, body)), body)
        }
    }

    @Test
    fun `a create still refused for its credential is unverified`() = runTest {
        assertEquals(CreateResult.Unverified, answered(UNAUTHORIZED))
    }

    @Test
    fun `every other answer is no answer, with how the service failed`() = runTest {
        for ((reply, fault) in listOf(
            text(502, "") to ServiceFault.TROUBLE,
            HttpResult.Response(503, mapOf("Retry-After" to "60"), "maintenance") to ServiceFault.UPDATING,
            json(201, "<html/>") to ServiceFault.UNREACHABLE,
            HttpResult.Unreachable("offline") to ServiceFault.UNREACHABLE,
            // A refusal no service names — this build too old — is told as the service not reached, never its failure.
            json(426, """{"error":"app too old","minAppVersion":"9.9"}""") to ServiceFault.UNREACHABLE,
        )) {
            assertEquals(CreateResult.Failed(fault), answered(reply), "$reply")
        }
    }
}
