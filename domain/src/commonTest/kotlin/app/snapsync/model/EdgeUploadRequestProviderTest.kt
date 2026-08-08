package app.snapsync.model

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

    private fun provider(
        host: String = "https://edge.example",
        token: suspend () -> String? = { "tok-1" },
    ) = EdgeUploadRequestProvider(host, deviceId, token)

    @Test
    fun builds_the_edge_url_for_an_unreserved_filename() = runTest {
        val req = provider().provide(resource("ABC123_DEF-primary.jpg"))
        assertEquals(
            "https://edge.example/files/devices/$deviceId/ABC123_DEF-primary.jpg",
            req.url,
        )
    }

    @Test
    fun percent_encodes_reserved_bytes_and_slash() = runTest {
        // Space → %20, `/` → %2F, multi-byte UTF-8 (ä = C3 A4) → %C3%A4, all uppercase hex.
        val req = provider().provide(resource("a b/ä.jpg"))
        assertTrue(req.url.endsWith("/files/devices/$deviceId/a%20b%2F%C3%A4.jpg"), "was ${req.url}")
    }

    @Test
    fun headers_are_exactly_content_type_and_the_device_token_no_metadata() = runTest {
        // The byte route is GATED (capability `device-attestation`), so the request carries the token —
        // and still nothing else: no `Host` (URL-implied), and no `x-*-meta-*` even though the resource
        // has metadata (the bunny native Storage API has no metadata headers).
        val req = provider().provide(resource("x.jpg", contentType = "image/heic"))
        assertEquals(
            mapOf("Content-Type" to "image/heic", "Authorization" to "Bearer tok-1"),
            req.headers,
        )
    }

    @Test
    fun content_type_is_the_mime_type_not_the_photokit_uti() = runTest {
        // On iOS `Resource.contentType` is the PhotoKit UTI (`public.jpeg`) — a value no HTTP client,
        // CDN or browser understands, and one that was reaching the origin verbatim (measured on device
        // 2026-08-07). The MIME rides in the resource metadata, resolved by `UTType.preferredMIMEType`,
        // and every other consumer already prefers it (`toLedgerRow`). So does this header.
        val req = provider().provide(
            Resource(
                filename = "x.jpg",
                assetId = "asset-1",
                contentType = "public.jpeg",
                metadata = mapOf(RESOURCE_META_MIME to "image/jpeg"),
                data = ByteArray(0),
            ),
        )
        assertEquals("image/jpeg", req.headers["Content-Type"])
    }

    @Test
    fun falls_back_to_the_resource_type_when_no_mime_metadata_is_carried() = runTest {
        // The retry path rebuilds a `Resource` from the job key alone, with EMPTY metadata — the platform
        // recovers the type from the job's stored destination header instead, and it arrives here as
        // `contentType`. A blank metadata value is treated as absent for the same reason.
        val rebuilt = provider().provide(resource("x.jpg", contentType = "image/heic"))
        assertEquals("image/heic", rebuilt.headers["Content-Type"])

        val blank = provider().provide(
            Resource("x.jpg", "asset-1", "image/heic", mapOf(RESOURCE_META_MIME to "  "), ByteArray(0)),
        )
        assertEquals("image/heic", blank.headers["Content-Type"])
    }

    @Test
    fun the_token_is_read_per_call_so_a_retry_picks_up_a_refreshed_one() = runTest {
        // This is what heals an expired token with no special-casing anywhere: the engine re-mints the
        // request from this provider on every retry. A provider that captured the token at construction
        // would keep re-sending the dead one forever.
        var current: String? = "stale"
        val p = EdgeUploadRequestProvider("https://edge.example", deviceId) { current }

        val before = p.provide(resource("x.jpg"))
        assertEquals("Bearer stale", before.headers["Authorization"])

        current = "fresh" // the app renewed in the background

        val after = p.provide(resource("x.jpg"))
        assertEquals("Bearer fresh", after.headers["Authorization"])
        assertEquals(before.url, after.url) // …and the destination is byte-identical, as before
    }

    @Test
    fun a_missing_token_still_yields_a_request() = runTest {
        // Deliberately not a failure. A request with no token 401s, and a 401 is retryable; refusing to
        // BUILD one would strand the resource instead. This is the normal state of a device that has not
        // attested yet — and of the extension on a device whose token expired, since it cannot renew.
        val req = provider(token = { null }).provide(resource("x.jpg", contentType = "image/heic"))
        assertEquals(mapOf("Content-Type" to "image/heic"), req.headers)
    }

    @Test
    fun url_has_no_query_string() = runTest {
        val req = provider().provide(resource("x.jpg"))
        // The credential is in the HEADER, never the URL — which is what keeps the URL stable and
        // expiry-free, so a retry re-derived hours later re-PUTs a byte-identical destination.
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
            "https://edge.example/files/devices/$deviceId/x.jpg",
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
