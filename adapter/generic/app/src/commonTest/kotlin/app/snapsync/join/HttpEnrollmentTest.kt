package app.snapsync.join

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The Ktor adapter behind the `Enrollment` port — the synchronous, in-cycle upload of a device's
 * manifest, and (via `ManifestDeviceEnroller`) the register-only empty manifest written at join.
 *
 * Its contract is one boolean: `true` **only** when the edge confirmed the store, because the producer
 * records the snapshot as last-uploaded on that answer alone. So a transport failure and a refusal both
 * read `false` — a manifest recorded as stored but never written would leave this device's photos out
 * of the event union with nothing left to retry it.
 *
 * The manifest JSON is passed through **verbatim**: composing it is `feature/upload`'s job
 * (`DeviceManifestProducer`), and this adapter is a dumb sender.
 */
class HttpEnrollmentTest {

    private val eventId = "22222222-2222-4222-8222-222222222222"
    private val deviceId = "11111111-1111-4111-8111-111111111111"
    private val manifest = """{"files":["a.heic"]}"""

    private fun client(handler: MockEngine) =
        HttpEnrollment(HttpClient(handler), "https://edge.example/")

    @Test
    fun `a 2xx PUTs the manifest verbatim to the per-event device route and confirms`() = runTest {
        var requested: String? = null
        var method: String? = null
        var body: String? = null
        var contentType: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            method = request.method.value
            body = (request.body as TextContent).text
            contentType = (request.body as TextContent).contentType.toString()
            respond("", HttpStatusCode.OK)
        }

        assertTrue(client(engine).put(eventId, deviceId, manifest))
        assertEquals("https://edge.example/events/$eventId/devices/$deviceId", requested)
        assertEquals("PUT", method)
        assertTrue(contentType!!.startsWith("application/json"), "content type was $contentType")
        assertEquals(manifest, body)
    }

    @Test
    fun `an empty register-only manifest is sent as given`() = runTest {
        // What `ManifestDeviceEnroller` writes at join: the device registers before it has uploaded
        // anything, so an empty file list is a legitimate body, not a bug to defend against.
        var body: String? = null
        val engine = MockEngine { request ->
            body = (request.body as TextContent).text
            respond("", HttpStatusCode.Created)
        }
        assertTrue(client(engine).put(eventId, deviceId, """{"files":[]}"""))
        assertEquals("""{"files":[]}""", body)
    }

    @Test
    fun `a refusal and a transport failure alike read as unconfirmed`() = runTest {
        // Both must be `false`: the producer records "last uploaded" on `true` only, so a wrong `true`
        // here drops this device out of the event union permanently.
        assertFalse(client(MockEngine { respondError(HttpStatusCode.Unauthorized) }).put(eventId, deviceId, manifest))
        assertFalse(client(MockEngine { respondError(HttpStatusCode.BadGateway) }).put(eventId, deviceId, manifest))
        assertFalse(client(MockEngine { throw RuntimeException("offline") }).put(eventId, deviceId, manifest))
    }

    @Test
    fun `a host without a trailing slash addresses the same route`() = runTest {
        var requested: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            respond("", HttpStatusCode.OK)
        }
        HttpEnrollment(HttpClient(engine), "https://edge.example").put(eventId, deviceId, manifest)
        assertEquals("https://edge.example/events/$eventId/devices/$deviceId", requested)
    }
}
