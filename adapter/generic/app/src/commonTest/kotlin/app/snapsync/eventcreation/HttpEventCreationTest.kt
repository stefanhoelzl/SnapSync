package app.snapsync.eventcreation

import app.snapsync.ports.CreateOutcome

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

class HttpEventCreationTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"

    private fun client(handler: MockEngine) =
        HttpEventCreation(HttpClient(handler), "https://edge.example/")

    @Test
    fun `201 parses the event id and posts the trimmed name to the event route`() = runTest {
        var requested: String? = null
        var method: String? = null
        var body: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            method = request.method.value
            body = (request.body as TextContent).text
            respond(
                content =
                    """{"eventId":"$eventId","name":"My Party","createdAt":"2026-06-27T10:00:00.182Z","startsAt":"2026-07-14T18:00:00Z","endsAt":"2026-07-21T18:00:00Z"}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        // The client is a dumb sender — trimming is the use-case's job (see CreateEventTest).
        val outcome = client(engine).create("My Party", "2026-07-14T18:00:00Z", "2026-07-21T18:00:00Z")

        assertEquals("https://edge.example/events", requested)
        assertEquals("POST", method)
        // A non-null endsAt rides in the body verbatim, after startsAt.
        assertEquals(
            """{"name":"My Party","startsAt":"2026-07-14T18:00:00Z","endsAt":"2026-07-21T18:00:00Z"}""",
            body,
        )
        assertEquals(CreateOutcome.Created(eventId, "My Party"), outcome)
    }

    @Test
    fun `a null endsAt is omitted from the request body`() = runTest {
        var body: String? = null
        val engine = MockEngine { request ->
            body = (request.body as TextContent).text
            respond(
                content =
                    """{"eventId":"$eventId","name":"My Party","createdAt":"2026-06-27T10:00:00.182Z","startsAt":"2026-07-14T18:00:00Z"}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        // A null endsAt is the backend's legacy `+30d` fallback signal — it must NOT appear in the body.
        val outcome = client(engine).create("My Party", "2026-07-14T18:00:00Z", null)

        assertEquals("""{"name":"My Party","startsAt":"2026-07-14T18:00:00Z"}""", body)
        assertEquals(CreateOutcome.Created(eventId, "My Party"), outcome)
    }

    @Test
    fun `400 maps to the invalid-name outcome`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadRequest) }
        assertEquals(
            CreateOutcome.InvalidName,
            client(engine).create("x", "2026-07-14T18:00:00Z", "2026-07-21T18:00:00Z"),
        )
    }

    @Test
    fun `502 maps to the transient outcome`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadGateway) }
        assertEquals(
            CreateOutcome.Transient,
            client(engine).create("x", "2026-07-14T18:00:00Z", "2026-07-21T18:00:00Z"),
        )
    }

    @Test
    fun `a malformed 201 body maps to the transient outcome`() = runTest {
        val engine = MockEngine {
            respond("not json", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertEquals(
            CreateOutcome.Transient,
            client(engine).create("x", "2026-07-14T18:00:00Z", "2026-07-21T18:00:00Z"),
        )
    }

    @Test
    fun `a transport failure maps to the transient outcome`() = runTest {
        val engine = MockEngine { throw RuntimeException("boom") }
        assertTrue(
            client(engine).create("x", "2026-07-14T18:00:00Z", "2026-07-21T18:00:00Z") is CreateOutcome.Transient,
        )
    }
}
