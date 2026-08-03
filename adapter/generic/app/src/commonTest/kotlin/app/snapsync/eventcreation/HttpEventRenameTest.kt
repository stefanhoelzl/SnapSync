package app.snapsync.eventcreation

import app.snapsync.ports.RenameOutcome

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HttpEventRenameTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"

    private fun client(handler: MockEngine) =
        HttpEventRename(HttpClient(handler), "https://edge.example/")

    private fun event(name: String) =
        """{"eventId":"$eventId","name":"$name","createdAt":"2026-06-27T10:00:00.182Z",""" +
            """"startsAt":"2026-07-14T18:00:00Z","endsAt":"2026-07-21T18:00:00Z","capacity":10,""" +
            """"deletesAt":"2026-07-27T10:00:00Z"}"""

    @Test
    fun `200 PATCHes the name to the event route and returns the echoed name`() = runTest {
        var requested: String? = null
        var method: String? = null
        var body: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            method = request.method.value
            body = (request.body as TextContent).text
            respond(
                content = event("Ana's 30th"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val outcome = client(engine).rename(eventId, "Ana's 30th")

        assertEquals("https://edge.example/events/$eventId", requested)
        assertEquals("PATCH", method)
        // Name-only: the client can express no other field change, which is the point of the route.
        assertEquals("""{"name":"Ana's 30th"}""", body)
        assertEquals(RenameOutcome.Renamed("Ana's 30th"), outcome)
    }

    @Test
    fun `the ECHOED name is returned — not the submitted one`() = runTest {
        // The backend trims; the echo is authoritative. A client that reported its own input back would
        // let the persisted name drift from the marker by exactly the whitespace the backend removed.
        val engine = MockEngine {
            respond(
                content = event("Ana's 30th"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        assertEquals(RenameOutcome.Renamed("Ana's 30th"), client(engine).rename(eventId, "  Ana's 30th  "))
    }

    @Test
    fun `400 maps to the invalid-name outcome`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadRequest) }
        assertEquals(RenameOutcome.InvalidName, client(engine).rename(eventId, "x"))
    }

    @Test
    fun `404 maps to the TRANSIENT outcome — never a distinct event-gone one`() = runTest {
        // The single-witness rule (capability `leave-event`): a 404 here must not acquire a meaning of
        // its own, or a future change can wire a teardown to it. See RenameOutcome.Transient.
        val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
        assertEquals(RenameOutcome.Transient, client(engine).rename(eventId, "x"))
    }

    @Test
    fun `500 maps to the transient outcome`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        assertEquals(RenameOutcome.Transient, client(engine).rename(eventId, "x"))
    }

    @Test
    fun `502 maps to the transient outcome`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadGateway) }
        assertEquals(RenameOutcome.Transient, client(engine).rename(eventId, "x"))
    }

    @Test
    fun `a malformed 200 body maps to the transient outcome`() = runTest {
        val engine = MockEngine {
            respond("not json", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertEquals(RenameOutcome.Transient, client(engine).rename(eventId, "x"))
    }

    @Test
    fun `a 200 carrying no name maps to the transient outcome`() = runTest {
        // Malformed, not a nameless success — this client never invents the value it reports.
        val engine = MockEngine {
            respond(
                content = """{"eventId":"$eventId","createdAt":"2026-06-27T10:00:00.182Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        assertEquals(RenameOutcome.Transient, client(engine).rename(eventId, "x"))
    }

    @Test
    fun `a transport failure maps to the transient outcome`() = runTest {
        val engine = MockEngine { throw RuntimeException("boom") }
        assertTrue(client(engine).rename(eventId, "x") is RenameOutcome.Transient)
    }
}
