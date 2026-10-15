package app.snapsync.data.event

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.json
import app.snapsync.data.backend.ok
import app.snapsync.data.backend.text
import app.snapsync.domain.event.EventKeyId
import app.snapsync.domain.event.EventLookupFact
import app.snapsync.domain.event.MemberCounts
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.service.ServiceFault
import app.snapsync.format.TimestampText
import app.snapsync.ports.HttpResult
import app.snapsync.testids.testEventId
import app.snapsync.wire.models.EventChange
import app.snapsync.wire.models.EventDetails
import app.snapsync.wire.runtime.WireJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import app.snapsync.wire.models.Event as WireEvent
import app.snapsync.wire.models.MemberCounts as WireCounts

private const val ID = "0f0e8a5c-3f1b-4c2d-9e7a-1b2c3d4e5f60"

/** The server's word about an event, and its rename: only "not found" is a witness, and nothing is invented. */
class EventDirectoryTest {

    private val transport = ScriptedTransport()
    private val gateway = BackendGateway(exchangeOver(transport), ScriptedCredential("T1"))
    private val directory = EventDirectory(gateway)

    private fun details(
        name: String = "Lake weekend",
        startsAt: String = "2026-07-14T18:00:00Z",
        openUntil: String = "2026-07-28T18:00:00Z",
        completedAt: String? = null,
        keyId: String? = "k1",
    ) = EventDetails(
        eventId = ID,
        name = name,
        createdAt = "2026-07-01T10:00:00Z",
        startsAt = startsAt,
        endsAt = "2026-07-21T18:00:00Z",
        openUntil = openUntil,
        deletesAt = "2026-08-21T18:00:00Z",
        closedAt = null,
        completedAt = completedAt,
        keyId = keyId,
        members = WireCounts(active = 3, `final` = 1, waiting = 2),
    )

    private val maintenance = json(503, """{"error":"maintenance"}""", mapOf("Retry-After" to " 60 "))

    private fun answering(result: HttpResult) {
        transport.answer = { result }
    }

    private fun found(details: EventDetails) = json(200, WireJson.encodeToString(EventDetails.serializer(), details))

    @Test
    fun `a found event carries every fact a membership keeps`() = runTest {
        answering(found(details()))
        val found = assertIs<EventLookupFact.Found>(directory.lookup(testEventId(ID)))
        assertEquals(false, found.completed)
        with(found.event) {
            assertEquals(testEventId(ID), id)
            assertEquals("Lake weekend", name.text)
            assertEquals(TimestampText.parse("2026-07-14T18:00:00Z"), range.start.at)
            assertEquals(TimestampText.parse("2026-07-21T18:00:00Z"), range.end.at)
            assertEquals(OpenUntil(TimestampText.parse("2026-07-28T18:00:00Z")), openUntil)
            assertEquals(TimestampText.parse("2026-08-21T18:00:00Z"), deletesAt.at)
            assertEquals(EventKeyId("k1"), keyId)
            assertEquals(MemberCounts(3, 1, 2), members)
        }
    }

    @Test
    fun `the server's word that an event completed rides with it`() = runTest {
        answering(found(details(completedAt = "2026-08-01T00:00:00Z", keyId = null)))
        val found = assertIs<EventLookupFact.Found>(directory.lookup(testEventId(ID)))
        assertEquals(true, found.completed)
        assertEquals(null, found.event.keyId)
    }

    @Test
    fun `an unreadable open-until date costs only that date`() = runTest {
        answering(found(details(openUntil = "soon")))
        assertEquals(null, assertIs<EventLookupFact.Found>(directory.lookup(testEventId(ID))).event.openUntil)
    }

    @Test
    fun `not found is the one witness, and every other answer is none, told by how the service failed`() = runTest {
        answering(ok(404))
        assertEquals(EventLookupFact.NotFound, directory.lookup(testEventId(ID)))
        for ((answer, fault) in listOf(
            text(502, "down") to ServiceFault.TROUBLE,
            text(400, "bad") to ServiceFault.UNREACHABLE,
            HttpResult.Unreachable("offline") to ServiceFault.UNREACHABLE,
            json(200, "<html/>") to ServiceFault.UNREACHABLE,
        )) {
            answering(answer)
            assertEquals(EventLookupFact.Unanswered(fault), directory.lookup(testEventId(ID)), "$answer")
        }
    }

    @Test
    fun `a service being updated says when asking again is worth it`() = runTest {
        answering(maintenance)
        assertEquals(EventLookupFact.Unanswered(ServiceFault.UPDATING, 60), directory.lookup(testEventId(ID)))
        answering(json(503, """{"error":"maintenance"}""", mapOf("Retry-After" to "soon")))
        assertEquals(EventLookupFact.Unanswered(ServiceFault.UPDATING), directory.lookup(testEventId(ID)))
    }

    @Test
    fun `a found event this device cannot read is no answer, never one with an invented fact`() = runTest {
        // An answer the app does not understand is told as the service not reached.
        val unreadable = EventLookupFact.Unanswered(ServiceFault.UNREACHABLE)
        for (bad in listOf(
            details(name = "  "),
            details(startsAt = "someday"),
            details(startsAt = "2026-07-22T00:00:00Z"),
        )) {
            answering(found(bad))
            assertEquals(unreadable, directory.lookup(testEventId(ID)), "$bad")
        }
        answering(found(details().copy(endsAt = "never")))
        assertEquals(unreadable, directory.lookup(testEventId(ID)))
        answering(found(details().copy(deletesAt = "never")))
        assertEquals(unreadable, directory.lookup(testEventId(ID)))
    }

    // ---- the rename ----

    private val rename = EventRename(gateway)

    private fun changed(name: String) = json(
        200,
        WireJson.encodeToString(
            WireEvent.serializer(),
            WireEvent(
                eventId = ID,
                name = name,
                createdAt = "2026-07-01T10:00:00Z",
                startsAt = "2026-07-14T18:00:00Z",
                endsAt = "2026-07-21T18:00:00Z",
                openUntil = "2026-07-21T18:00:00Z",
                deletesAt = "2026-08-21T18:00:00Z",
                closedAt = null,
                completedAt = null,
            ),
        ),
    )

    @Test
    fun `a rename keeps the name the server stored`() = runTest {
        answering(changed("Lake week"))
        assertEquals(
            RenameFact.Renamed(app.snapsync.data.membership.name("Lake week")),
            rename.rename(testEventId(ID), app.snapsync.data.membership.name(" Lake week ")),
        )
        val sent = WireJson.decodeFromString(EventChange.serializer(), transport.requests.last().body!!)
        assertEquals("Lake week", sent.name, "the name is sent as the domain holds it, trimmed")
    }

    @Test
    fun `a refused name is refused, the photos deleted is the event over, and every other answer is none`() = runTest {
        answering(text(400, "bad name"))
        assertEquals(RenameFact.Refused, rename.rename(testEventId(ID), app.snapsync.data.membership.name("x")))
        answering(json(410, """{"error":"closed"}"""))
        assertEquals(RenameFact.EventOver, rename.rename(testEventId(ID), app.snapsync.data.membership.name("x")))
        for ((answer, fault) in listOf(
            // A lone "not found" is an answer from a working service, told generically — and no witness of a deletion.
            text(404, "event not found") to null,
            text(502, "down") to ServiceFault.TROUBLE,
            maintenance to ServiceFault.UPDATING,
            HttpResult.Unreachable("offline") to ServiceFault.UNREACHABLE,
            changed("  ") to ServiceFault.UNREACHABLE,
            changed("x".repeat(101)) to ServiceFault.UNREACHABLE,
        )) {
            answering(answer)
            assertEquals(
                RenameFact.Unanswered(fault),
                rename.rename(testEventId(ID), app.snapsync.data.membership.name("x")),
                "$answer",
            )
        }
    }
}
