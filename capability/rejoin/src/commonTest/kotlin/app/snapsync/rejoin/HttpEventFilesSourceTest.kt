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
    fun `parses the complete-asset array and targets the files route`() = runTest {
        var requested: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            respond(
                content = """[
                  {"assetId":"A","creationDate":"2026-06-27T00:00:00Z","resources":[
                    {"role":"primary","filename":"A-primary.heic","contentType":"image/heic","originalFilename":"IMG_0001.HEIC","url":"https://edge.example/event/$eventId/file/A-primary.heic"},
                    {"role":"live","filename":"A-live.mov","contentType":"video/quicktime","originalFilename":"IMG_0001.MOV","url":"https://edge.example/event/$eventId/file/A-live.mov"}
                  ]},
                  {"assetId":"B","creationDate":"2026-06-27T00:01:00Z","resources":[
                    {"role":"primary","filename":"B-primary.mov","contentType":"video/quicktime","originalFilename":"VID_0002.MOV","url":"https://edge.example/event/$eventId/file/B-primary.mov"}
                  ]}
                ]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val result = source(engine).list(eventId)

        assertEquals("https://edge.example/event/$eventId/files", requested)
        val assets = result.getOrThrow()
        // The join reads only assetId + each resource's filename; role/contentType/originalFilename/url
        // are ignored unknown keys.
        assertEquals(listOf("A", "B"), assets.map { it.assetId })
        assertEquals(listOf("A-primary.heic", "A-live.mov"), assets[0].resources.map { it.filename })
        assertEquals(listOf("B-primary.mov"), assets[1].resources.map { it.filename })
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
