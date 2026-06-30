package app.snapsync.uploadurl

import app.snapsync.engine.Resource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EdgeUploadRequestProviderTest {

    private val deviceId = "11111111-1111-4111-8111-111111111111"

    private fun resource(filename: String, contentType: String = "image/jpeg") = Resource(
        filename = filename,
        assetId = "asset-1",
        contentType = contentType,
        metadata = mapOf("ignored" to "value"),
        data = ByteArray(0),
    )

    private fun provider(host: String = "https://edge.example") =
        EdgeUploadRequestProvider(host, deviceId)

    @Test
    fun builds_the_edge_url_for_an_unreserved_filename() = runTest {
        val req = provider().provide(resource("ABC123_DEF-primary.jpg"))
        assertEquals(
            "https://edge.example/files/device/$deviceId/ABC123_DEF-primary.jpg",
            req.url,
        )
    }

    @Test
    fun percent_encodes_reserved_bytes_and_slash() = runTest {
        // Space → %20, `/` → %2F, multi-byte UTF-8 (ä = C3 A4) → %C3%A4, all uppercase hex.
        val req = provider().provide(resource("a b/ä.jpg"))
        assertTrue(req.url.endsWith("/files/device/$deviceId/a%20b%2F%C3%A4.jpg"), "was ${req.url}")
    }

    @Test
    fun headers_are_exactly_content_type_no_auth_no_metadata() = runTest {
        val req = provider().provide(resource("x.jpg", contentType = "image/heic"))
        assertEquals(mapOf("Content-Type" to "image/heic"), req.headers)
    }

    @Test
    fun url_has_no_query_string() = runTest {
        val req = provider().provide(resource("x.jpg"))
        assertTrue(!req.url.contains("?"), "edge URL must carry no auth query string: ${req.url}")
    }

    @Test
    fun resource_instance_round_trips() = runTest {
        val r = resource("x.jpg")
        val req = provider().provide(r)
        assertSame(r, req.resource)
    }

    @Test
    fun distinct_filenames_never_collide() = runTest {
        val a = provider().provide(resource("one.jpg")).url
        val b = provider().provide(resource("two.jpg")).url
        assertTrue(a != b)
    }

    @Test
    fun trailing_slash_on_host_is_normalized() = runTest {
        val req = provider(host = "https://edge.example/").provide(resource("x.jpg"))
        assertEquals(
            "https://edge.example/files/device/$deviceId/x.jpg",
            req.url,
        )
    }

    @Test
    fun rebuild_is_byte_identical() = runTest {
        val p = provider()
        val r = resource("x.jpg")
        assertEquals(p.provide(r).url, p.provide(r).url)
    }
}
