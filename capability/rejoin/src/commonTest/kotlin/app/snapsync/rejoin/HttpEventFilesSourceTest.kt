package app.snapsync.rejoin

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HttpEventFilesSourceTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"

    private fun source(handler: MockEngine) = HttpEventFilesSource(HttpClient(handler), "https://edge.example/")

    @Test
    fun `parses the flat array and targets the files route`() = runTest {
        var requested: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            respond(
                content = """[
                  {"filename":"A-ios.photo.heic","size":1,"url":"https://edge.example/event/$eventId/file/A-ios.photo.heic"},
                  {"filename":"B-ios.video.mov","size":2,"url":"https://edge.example/event/$eventId/file/B-ios.video.mov"}
                ]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val result = source(engine).list(eventId)

        assertEquals("https://edge.example/event/$eventId/files", requested)
        val files = result.getOrThrow()
        // The join parses only `filename`; `size`/`url` are ignored unknown keys.
        assertEquals(listOf("A-ios.photo.heic", "B-ios.video.mov"), files.map { it.filename })
    }

    @Test
    fun `empty array yields an empty list`() = runTest {
        val engine = MockEngine { respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertEquals(emptyList(), source(engine).list(eventId).getOrThrow())
    }

    @Test
    fun `non-2xx is a failed Result rather than a throw`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadGateway) }
        assertTrue(source(engine).list(eventId).isFailure)
    }

    @Test
    fun `malformed body is a failed Result`() = runTest {
        val engine = MockEngine { respond("not json", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertTrue(source(engine).list(eventId).isFailure)
    }
}
