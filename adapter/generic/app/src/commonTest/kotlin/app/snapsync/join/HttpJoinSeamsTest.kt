package app.snapsync.join

import app.snapsync.ports.JoinResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two seams the join split produced (capability `join-event`).
 *
 * They are tested together because what they are FOR is the separation: one owns membership and decides
 * capacity, the other owns what a device shares. The pair used to be a single request whose body was a
 * register-only empty manifest — which made enrolment a second writer of a document the upload cycle
 * owns, and blanked a rejoining device's contribution until the next cycle republished it.
 */
class HttpJoinSeamsTest {

    private val eventId = "7a3f9c21-0000-4000-8000-000000000001"
    private val deviceId = "11111111-0000-4000-8000-000000000002"
    private val host = "https://edge.example/api/v2/"

    private fun engine(status: HttpStatusCode, record: ((HttpRequestData) -> Unit)? = null) =
        MockEngine { request ->
            record?.invoke(request)
            respond(content = "", status = status)
        }

    // ---- the join ----------------------------------------------------------------------------

    @Test
    fun `the join is a bodyless PUT at the event-device route`() = runTest {
        // Bodyless is the contract, not an omission: a body here would be a manifest, and writing one
        // is exactly what this seam stopped doing.
        var url: String? = null
        var method: String? = null
        var body: ByteReadChannel? = null
        val e = engine(HttpStatusCode.OK) { r ->
            url = r.url.toString(); method = r.method.value
            body = (r.body as? io.ktor.http.content.OutgoingContent.ReadChannelContent)?.readFrom()
        }

        assertEquals(JoinResult.JOINED, HttpEventJoin(HttpClient(e), host).join(eventId, deviceId))

        assertEquals("PUT", method)
        assertEquals("https://edge.example/api/v2/events/$eventId/devices/$deviceId", url)
        assertNull(body, "the join carries no body")
    }

    @Test
    fun `a full event is 409 and an absent one is 404 — neither is a transport failure`() = runTest {
        // The mapping this seam exists for. All three used to answer `false`, which is how "this event
        // is full" and "the network blipped" became one message on the join screen.
        suspend fun join(status: HttpStatusCode) =
            HttpEventJoin(HttpClient(engine(status)), host).join(eventId, deviceId)

        assertEquals(JoinResult.EVENT_FULL, join(HttpStatusCode.Conflict))
        assertEquals(JoinResult.EVENT_NOT_FOUND, join(HttpStatusCode.NotFound))
        assertEquals(JoinResult.FAILED, join(HttpStatusCode.InternalServerError))
        assertEquals(JoinResult.FAILED, join(HttpStatusCode.Unauthorized))
    }

    @Test
    fun `a thrown transport failure is a FAILED result rather than an exception`() = runTest {
        // The port answers a value; a caller mid-join must not have to catch.
        val e = MockEngine { throw kotlinx.io.IOException("offline") }
        assertEquals(JoinResult.FAILED, HttpEventJoin(HttpClient(e), host).join(eventId, deviceId))
    }

    // ---- the publish -------------------------------------------------------------------------

    @Test
    fun `a host without a trailing slash addresses the same routes`() = runTest {
        // Carried over from the enrollment seam this pair replaces: both hosts must compose one `/`, or
        // a deployment whose base is written either way silently addresses `//events`.
        var joinUrl: String? = null
        var publishUrl: String? = null
        val bare = "https://edge.example/api/v2"
        HttpEventJoin(HttpClient(engine(HttpStatusCode.OK) { joinUrl = it.url.toString() }), bare)
            .join(eventId, deviceId)
        HttpManifestPublisher(HttpClient(engine(HttpStatusCode.OK) { publishUrl = it.url.toString() }), bare)
            .publish(eventId, deviceId, "{}")

        assertEquals("https://edge.example/api/v2/events/$eventId/devices/$deviceId", joinUrl)
        assertEquals("https://edge.example/api/v2/events/$eventId/devices/$deviceId/manifest", publishUrl)
    }

    @Test
    fun `the manifest body is sent verbatim`() = runTest {
        // The projection is built and serialized upstream; this seam is a transport. Re-encoding here
        // would let the stored document drift from the one the producer composed.
        val body = """{"deviceId":"$deviceId","assets":[]}"""
        var sent: String? = null
        val e = MockEngine { r ->
            sent = (r.body as io.ktor.http.content.TextContent).text
            respond(content = "", status = HttpStatusCode.OK)
        }
        HttpManifestPublisher(HttpClient(e), host).publish(eventId, deviceId, body)
        assertEquals(body, sent)
    }

    @Test
    fun `the publish is a JSON PUT at the manifest SUB-resource`() = runTest {
        // The sub-resource path is the separation made visible on the wire: publishing cannot be
        // mistaken for joining, and a device that has left cannot re-enrol itself by contributing.
        var url: String? = null
        var contentType: String? = null
        val e = engine(HttpStatusCode.OK) { r ->
            url = r.url.toString()
            contentType = r.body.contentType?.toString()
        }

        assertTrue(HttpManifestPublisher(HttpClient(e), host).publish(eventId, deviceId, """{"a":1}"""))

        assertEquals("https://edge.example/api/v2/events/$eventId/devices/$deviceId/manifest", url)
        assertEquals("application/json", contentType)
    }

    @Test
    fun `a refused publish is false — and so is a transport failure`() = runTest {
        // A non-member's publish is refused rather than silently creating a membership — which is the
        // divergence the world harness models too. Both reach the caller as "not published".
        assertFalse(
            HttpManifestPublisher(HttpClient(engine(HttpStatusCode.Conflict)), host)
                .publish(eventId, deviceId, "{}"),
        )
        val e = MockEngine { throw kotlinx.io.IOException("offline") }
        assertFalse(HttpManifestPublisher(HttpClient(e), host).publish(eventId, deviceId, "{}"))
    }
}
