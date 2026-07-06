package app.snapsync.join

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpEventDetailsSourceTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"

    private fun source(handler: MockEngine) =
        HttpEventDetailsSource(HttpClient(handler), "https://edge.example/")

    @Test
    fun `200 yields Found with the name and createdAt from the event route`() = runTest {
        var requested: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            respond(
                content = """{"eventId":"$eventId","name":"Anna's Birthday","createdAt":"2026-06-27T10:00:00Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = source(engine).fetch(eventId)

        assertEquals("https://edge.example/events/$eventId", requested)
        assertEquals(EventDetails.Found("Anna's Birthday", "2026-06-27T10:00:00Z"), result)
    }

    @Test
    fun `404 yields NotFound`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
        assertEquals(EventDetails.NotFound, source(engine).fetch(eventId))
    }

    @Test
    fun `a 5xx yields Failed`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadGateway) }
        assertEquals(EventDetails.Failed, source(engine).fetch(eventId))
    }

    @Test
    fun `a parse failure yields Failed`() = runTest {
        val engine = MockEngine {
            respond("not json", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertEquals(EventDetails.Failed, source(engine).fetch(eventId))
    }
}
