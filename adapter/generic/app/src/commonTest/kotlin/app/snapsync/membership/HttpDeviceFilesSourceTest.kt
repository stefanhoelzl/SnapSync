package app.snapsync.membership

import app.snapsync.ports.DeviceListingShapeException
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
 * Two contracts of its own. **The identity fields are REQUIRED and the key is recomposed from them**:
 * the backend answers `assetId`, `role` and the resource's capture `filename`, and this seam rebuilds
 * the storage key through the shared `uploadKey`. That strictness replaced a lenient read of a field
 * called `filename`, which the frozen v1 shape also carries and means the STORAGE KEY by — so a lenient
 * decode pointed at the wrong version seeds capture names as ledger keys and re-uploads the library,
 * with no failed request anywhere. Unknown fields are still ignored, because a backend ADDING one must
 * not cost a device its dedup set.
 *
 * And **every failure is a failed [Result], never a throw**, so the reconciler can defer the cycle
 * rather than crash — but a SHAPE failure is a distinguishable type, because unlike a transport failure
 * it will never heal, and the message names the status, because "could not list" and "listed nothing"
 * are opposite answers here (an empty success seeds no dedup at all, which is correct only when the
 * device genuinely stored nothing).
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
    fun `200 GETs the device byte-store route and recomposes the keys in order`() = runTest {
        var requested: String? = null
        var method: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            method = request.method.value
            json(
                """[{"assetId":"a","role":"primary","filename":"IMG_1.HEIC"},
                    {"assetId":"b","role":"live","filename":"IMG_2.MOV"}]""",
                HttpStatusCode.OK,
            )
        }

        // The KEY, not the capture name: `<assetId>-<role>.<ext>`, exactly what the producer uploaded under.
        assertEquals(listOf("a-primary.heic", "b-live.mov"), source(engine).list(deviceId).getOrThrow())
        assertEquals("https://edge.example/files/devices/$deviceId", requested)
        assertEquals("GET", method)
    }

    @Test
    fun `size and url ride along as ignored unknown keys`() = runTest {
        // Load-bearing: a backend that adds a field must not cost this device its whole dedup set.
        val engine = MockEngine {
            json(
                """[{"assetId":"a","role":"primary","filename":"IMG_1.HEIC","size":1234,"url":"https://cdn/x"}]""",
                HttpStatusCode.OK,
            )
        }
        assertEquals(listOf("a-primary.heic"), source(engine).list(deviceId).getOrThrow())
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
    fun `the frozen v1 shape is refused distinguishably rather than read as capture names`() = runTest {
        // THE trap. Both shapes carry a field called `filename` and mean opposite things by it, so this
        // body decodes cleanly under a lenient reader and seeds nonsense. The type matters as much as the
        // failure: the reconciler reports a shape failure at `Error` and a transport failure as a retry.
        val v1 = MockEngine {
            json("""[{"filename":"a-primary.heic","url":"https://cdn/x"}]""", HttpStatusCode.OK)
        }
        val failure = source(v1).list(deviceId).exceptionOrNull()
        assertTrue(failure is DeviceListingShapeException, "was ${'$'}failure")
    }

    @Test
    fun `an unknown role is refused rather than defaulted`() = runTest {
        // `role` decodes against the closed vocabulary, not as an opaque string: a role this build cannot
        // name would compose a key that matches nothing stored.
        val unknown = MockEngine {
            json("""[{"assetId":"a","role":"thumbnail","filename":"IMG_1.HEIC"}]""", HttpStatusCode.OK)
        }
        assertTrue(source(unknown).list(deviceId).exceptionOrNull() is DeviceListingShapeException)
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
