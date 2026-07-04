package app.snapsync.eventcreation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class HttpEventMetadataSourceTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"

    private fun source(handler: MockEngine) =
        HttpEventMetadataSource(HttpClient(handler), "https://edge.example/")

    @Test
    fun `200 parses the name from the event route`() = runTest {
        var requested: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            respond(
                content = """{"eventId":"$eventId","name":"Anna's Birthday","createdAt":"2026-06-27T10:00:00Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val name = source(engine).name(eventId)

        assertEquals("https://edge.example/event/$eventId", requested)
        assertEquals("Anna's Birthday", name)
    }

    @Test
    fun `a 404 yields null for an unknown event and never throws`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
        assertNull(source(engine).name(eventId))
    }

    @Test
    fun `a transport or parse failure yields null`() = runTest {
        val engine = MockEngine {
            respond("not json", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertNull(source(engine).name(eventId))
    }
}
