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
        appVersion: String = "0.4",
    ) = EdgeUploadRequestProvider(host, deviceId, token, appVersion)

    @Test
    fun names_identity_in_the_path_and_the_capture_name_in_the_query() = runTest {
        val req = provider().provide(
            Resource(
                filename = "ABC123_DEF-primary.jpg",
                assetId = "ABC123_DEF",
                contentType = "image/jpeg",
                metadata = mapOf(RESOURCE_META_ORIGINAL_FILENAME to "IMG_0001.JPG"),
                data = ByteArray(0),
            ),
        )
        assertEquals(
            "https://edge.example/files/devices/$deviceId/ABC123_DEF/primary?filename=IMG_0001.JPG",
            req.url,
        )
    }

    @Test
    fun percent_encodes_reserved_bytes_in_every_composed_part() = runTest {
        // Space → %20, `/` → %2F, multi-byte UTF-8 (ä = C3 A4) → %C3%A4, all uppercase hex. The capture
        // name is the part that realistically carries them, and it must never reach a path segment.
        val req = provider().provide(
            Resource(
                filename = "a b-primary.jpg",
                assetId = "a b",
                contentType = "image/jpeg",
                metadata = mapOf(RESOURCE_META_ORIGINAL_FILENAME to "a b/ä.jpg"),
                data = ByteArray(0),
            ),
        )
        assertTrue(
            req.url.endsWith("/files/devices/$deviceId/a%20b/primary?filename=a%20b%2F%C3%A4.jpg"),
            "was ${req.url}",
        )
    }

    @Test
    fun a_rebuilt_resource_with_no_metadata_addresses_the_same_object() = runTest {
        // The retry path rebuilds a `Resource` from the job key alone, with EMPTY metadata. The capture
        // name then falls back to the KEY — exact, not approximate, because the endpoint consumes only
        // this value's EXTENSION when composing the stored object name, and the key shares it.
        val original = provider().provide(
            Resource(
                filename = "K-primary.jpg",
                assetId = "K",
                contentType = "image/jpeg",
                metadata = mapOf(RESOURCE_META_ORIGINAL_FILENAME to "IMG_9.JPG"),
                data = ByteArray(0),
            ),
        )
        val rebuilt = provider().provide(
            Resource("K-primary.jpg", "K", "image/jpeg", emptyMap(), ByteArray(0)),
        )
        // Same identity, so the endpoint composes the same object name; only the recorded capture
        // metadata differs.
        assertEquals(original.url.substringBefore("?"), rebuilt.url.substringBefore("?"))
        assertTrue(rebuilt.url.endsWith("?filename=K-primary.jpg"), "was ${rebuilt.url}")
    }

    @Test
    fun the_request_declares_the_app_version_because_the_os_performs_it() = runTest {
        // The shared HTTP client cannot add this: the platform issues this request later, outside any
        // client this app controls (capability `min-app-version`).
        val req = provider(appVersion = "0.4").provide(resource("x-primary.jpg"))
        assertEquals("0.4", req.headers[APP_VERSION_HEADER])
    }

    @Test
    fun headers_are_exactly_content_type_and_the_device_token_no_metadata() = runTest {
        // The byte route is GATED (capability `device-attestation`), so the request carries the token —
        // and still nothing else: no `Host` (URL-implied), and no `x-*-meta-*` even though the resource
        // has metadata (the bunny native Storage API has no metadata headers).
        val req = provider().provide(resource("x.jpg", contentType = "image/heic"))
        assertEquals(
            mapOf(
                "Content-Type" to "image/heic",
                APP_VERSION_HEADER to "0.4",
                "Authorization" to "Bearer tok-1",
            ),
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
        val p = EdgeUploadRequestProvider("https://edge.example", deviceId, { current }, "0.4")

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
        assertEquals(
            mapOf("Content-Type" to "image/heic", APP_VERSION_HEADER to "0.4"),
            req.headers,
        )
    }

    @Test
    fun url_carries_the_capture_name_and_no_credential_parameters() = runTest {
        val req = provider().provide(resource("x.jpg"))
        // The query is not free-for-all: it carries the mandatory capture name and NOTHING else. The
        // credential stays in the HEADER, never the URL — which is what keeps the destination stable and
        // expiry-free, so a retry re-derived hours later re-PUTs a byte-identical one.
        assertEquals("filename=x.jpg", req.url.substringAfter("?"))
        assertTrue(
            !req.url.contains("signature") && !req.url.contains("expire"),
            "edge URL must carry no credential parameters: ${req.url}",
        )
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
            "https://edge.example/files/devices/$deviceId/x/primary?filename=x.jpg",
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
