package app.snapsync.membership

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The Ktor adapter behind the `DeviceFilesSource` port — the **dedup** source the extension reconciler
 * seeds `COMPLETED` from. Bytes are device-partitioned and event-independent, so this listing is what
 * restores a reinstall's empty ledger and what preserves dedup across an event switch.
 *
 * Two contracts of its own. **It reads only `filename`**: the route's entries also carry `size` and
 * `url`, and treating either as required would turn a backend field addition into a device that
 * re-uploads everything it already stored. And **every failure is a failed [Result], never a throw**,
 * so the reconciler can defer the cycle rather than crash — but the message names the status, because
 * "could not list" and "listed nothing" are opposite answers here (an empty success seeds no dedup at
 * all, which is correct only when the device genuinely stored nothing).
 */
class HttpDeviceFilesSourceTest {

    private val deviceId = "11111111-1111-4111-8111-111111111111"

    private fun source(handler: MockEngine) =
        HttpDeviceFilesSource(HttpClient(handler), "https://edge.example/")

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    @Test
    fun `200 GETs the device byte-store route and yields the filenames in order`() = runTest {
        var requested: String? = null
        var method: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            method = request.method.value
            json("""[{"filename":"a.heic"},{"filename":"b.heic"}]""", HttpStatusCode.OK)
        }

        assertEquals(listOf("a.heic", "b.heic"), source(engine).list(deviceId).getOrThrow())
        assertEquals("https://edge.example/files/devices/$deviceId", requested)
        assertEquals("GET", method)
    }

    @Test
    fun `size and url ride along as ignored unknown keys`() = runTest {
        // Load-bearing: a backend that adds a field must not cost this device its whole dedup set.
        val engine = MockEngine {
            json(
                """[{"filename":"a.heic","size":1234,"url":"https://cdn/x","added":"2026-07-14"}]""",
                HttpStatusCode.OK,
            )
        }
        assertEquals(listOf("a.heic"), source(engine).list(deviceId).getOrThrow())
    }

    @Test
    fun `an empty listing is a success carrying no filenames`() = runTest {
        // Distinct from a failure below, and the difference matters: this one says the device stored
        // nothing, so there is nothing to dedup against.
        val engine = MockEngine { json("[]", HttpStatusCode.OK) }
        assertEquals(emptyList(), source(engine).list(deviceId).getOrThrow())
    }

    @Test
    fun `a non-2xx is a failed Result naming the status rather than a thrown error`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadGateway) }
        val result = source(engine).list(deviceId)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("502"), "message was '$message'")
        assertTrue(message.contains(deviceId), "message was '$message'")
    }

    @Test
    fun `a transport failure is a failed Result rather than a thrown error`() = runTest {
        val engine = MockEngine { throw RuntimeException("offline") }
        assertTrue(source(engine).list(deviceId).isFailure)
    }

    @Test
    fun `a malformed body is a failed Result rather than an empty listing`() = runTest {
        // The one that would be silent if it collapsed to success: an empty list seeds no dedup, so a
        // garbled 200 read as "nothing stored" makes the device re-upload its whole contribution.
        assertTrue(source(MockEngine { json("not json", HttpStatusCode.OK) }).list(deviceId).isFailure)
        val objectBody = MockEngine { json("""{"files":[]}""", HttpStatusCode.OK) }
        assertTrue(source(objectBody).list(deviceId).isFailure)
        val missingField = MockEngine { json("""[{"size":1}]""", HttpStatusCode.OK) }
        assertTrue(source(missingField).list(deviceId).isFailure)
    }

    @Test
    fun `a host without a trailing slash addresses the same route`() = runTest {
        var requested: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            json("[]", HttpStatusCode.OK)
        }
        HttpDeviceFilesSource(HttpClient(engine), "https://edge.example").list(deviceId)
        assertEquals("https://edge.example/files/devices/$deviceId", requested)
    }
}
