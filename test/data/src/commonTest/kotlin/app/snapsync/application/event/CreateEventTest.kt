package app.snapsync.application.event

import app.snapsync.application.EntryPointCall
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.json
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.NewEventKeys
import app.snapsync.data.event.EventCreation
import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.request.CreateRequest
import app.snapsync.domain.request.RequestId
import app.snapsync.format.TimestampText
import app.snapsync.mock.DevControlsMock
import app.snapsync.mock.fakeCrypto
import app.snapsync.testids.testEventId
import app.snapsync.wire.models.EventCreate
import app.snapsync.wire.runtime.WireJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Creating an event: an encrypted event's key minted on the creating device and only its id sent, the name trimmed, and
 * the minted event's id and key the answer — what the creator joins with.
 */
@OptIn(EntryPointCall::class)
class CreateEventTest {

    private val transport = ScriptedTransport {
        json(
            201,
            """{"eventId":"${testEventId("E1").value}","name":"Party","createdAt":"2026-06-01T00:00:00Z",""" +
                """"startsAt":"2026-07-01T00:00:00Z","endsAt":"2026-07-08T00:00:00Z",""" +
                """"openUntil":"2026-07-08T00:00:00Z",""" +
                """"capacity":40,"deletesAt":"2026-07-31T00:00:00Z","closedAt":null,"completedAt":null}""",
        )
    }
    private val controls = DevControlsMock()
    private val ids = EventKeyIds(fakeCrypto())
    private val create = CreateEvent(
        EventCreation(BackendGateway(exchangeOver(transport), ScriptedCredential("T1"))),
        NewEventKeys(ids, controls.port()),
    )

    private fun request() = CreateRequest(
        RequestId(1),
        "Party",
        EventStart(TimestampText.parse("2026-07-14T18:00:00Z")),
        EventEnd(TimestampText.parse("2026-07-21T18:00:00Z")),
        openUntil = null,
        zone = "Europe/Berlin",
    )

    private fun sent(): EventCreate = WireJson.decodeFromString(
        EventCreate.serializer(),
        transport.requests.last().body!!,
    )

    @Test
    fun `an encrypted create sends only the key id, and answers the key the creator joins with`() = runTest {
        val created = assertIs<CreateResult.Created>(create(request()))
        val key = assertNotNull(created.key)
        val keyId = assertNotNull(sent().keyId)
        assertEquals(ids.identify(key).id.value, keyId, "the answered key is the one whose id was sent")
        assertEquals(16, keyId.length, "only the key's 8-byte id is sent")
        assertEquals(testEventId("E1"), created.eventId)
    }

    @Test
    fun `a build that creates plain events mints no key and sends no key id`() = runTest {
        controls.encrypts = false
        val created = assertIs<CreateResult.Created>(create(request()))
        assertNull(sent().keyId)
        assertNull(created.key)
    }
}
