package app.snapsync.s3

import app.snapsync.engine.Resource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class S3UploadRequestProviderTest {

    private val config = S3Config(
        bucket = "bucket",
        region = "eu-central-1",
        endpoint = "https://s3.eu-central-1.amazonaws.com",
        accessKeyId = "AKIAEXAMPLE",
        secretAccessKey = "secretExampleKey",
    )
    private val clock = FixedClock(Instant.parse("2026-06-15T12:00:00Z"))

    private fun provider(
        expiresIn: kotlin.time.Duration = 7.days,
        cfg: S3Config = config,
    ) = S3UploadRequestProvider(cfg, expiresIn = expiresIn, clock = clock)

    private fun resource(
        filename: String = "id-ios.photo.jpg",
        contentType: String = "image/jpeg",
        metadata: Map<String, String> = emptyMap(),
    ) = Resource(filename, contentType, version = "v1", metadata = metadata, data = Unit)

    // --- 5.1 Object-key encoding -----------------------------------------------------------------

    @Test
    fun `unreserved filename passes through`() {
        assertEquals("abc-DEF_123.jpg", encodeObjectKeySegment("abc-DEF_123.jpg"))
    }

    @Test
    fun `reserved bytes percent-encode uppercase`() {
        assertEquals("a%20b", encodeObjectKeySegment("a b"))
        assertEquals("%C3%A4", encodeObjectKeySegment("ä")) // 'ä' UTF-8 → C3 A4
        assertEquals("%7E", encodeObjectKeySegment("~"))         // stricter than RFC 3986 on purpose
    }

    @Test
    fun `slash in filename is escaped and prefix slash stays literal`() = runTest {
        val url = provider().provide(resource(filename = "a/b-ios.photo.jpg")).url
        assertTrue(url.contains("/bucket/resources/a%2Fb-ios.photo.jpg"), url)
    }

    @Test
    fun `distinct filenames never collide`() {
        assertTrue(encodeObjectKeySegment("a/b") != encodeObjectKeySegment("a-b"))
        assertTrue(encodeObjectKeySegment("a b") != encodeObjectKeySegment("a+b"))
    }

    // --- 5.2 Metadata → headers ------------------------------------------------------------------

    @Test
    fun `metadata header name is lowercased and value verbatim`() = runTest {
        val headers = provider().provide(resource(metadata = mapOf("Asset-Id" to "Mixed Value 123"))).headers
        assertEquals("Mixed Value 123", headers["x-amz-meta-asset-id"])
    }

    @Test
    fun `empty metadata yields no meta headers`() = runTest {
        val headers = provider().provide(resource(metadata = emptyMap())).headers
        assertEquals(setOf("content-type"), headers.keys)
    }

    @Test
    fun `non-ascii metadata value throws`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            provider().provide(resource(metadata = mapOf("name" to "café")))
        }
    }

    @Test
    fun `crlf metadata value throws`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            provider().provide(resource(metadata = mapOf("name" to "a\r\nb")))
        }
    }

    // --- 5.3 Expiry policy -----------------------------------------------------------------------

    @Test
    fun `default expiry is seven days`() = runTest {
        assertTrue(provider().provide(resource()).url.contains("X-Amz-Expires=604800"))
    }

    @Test
    fun `expiry serialized as whole seconds`() = runTest {
        assertTrue(provider(expiresIn = 90.minutes).provide(resource()).url.contains("X-Amz-Expires=5400"))
    }

    @Test
    fun `rejects expiry over seven days`() {
        assertFailsWith<IllegalArgumentException> { provider(expiresIn = 8.days) }
    }

    @Test
    fun `rejects non-positive expiry`() {
        assertFailsWith<IllegalArgumentException> { provider(expiresIn = 0.minutes) }
        assertFailsWith<IllegalArgumentException> { provider(expiresIn = (-1).minutes) }
    }

    // --- 5.5 Returned shape, determinism, port ---------------------------------------------------

    @Test
    fun `headers exclude host but carry content-type and metadata`() = runTest {
        val headers = provider().provide(resource(metadata = mapOf("asset-id" to "X"))).headers
        assertFalse(headers.keys.any { it.equals("host", ignoreCase = true) })
        assertEquals("image/jpeg", headers["content-type"])
        assertEquals("X", headers["x-amz-meta-asset-id"])
    }

    @Test
    fun `url carries auth query and signature`() = runTest {
        val url = provider().provide(resource()).url
        assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"), url)
        assertTrue(url.contains("&X-Amz-Signature="), url)
    }

    @Test
    fun `returned request carries the same resource instance`() = runTest {
        val r = resource()
        assertTrue(provider().provide(r).resource === r)
    }

    @Test
    fun `fixed clock is reproducible`() = runTest {
        val p = provider()
        assertEquals(p.provide(resource()).url, p.provide(resource()).url)
    }

    @Test
    fun `host carries a non-default port`() {
        val portCfg = config.let {
            S3Config("bucket", "eu-central-1", "http://localhost:9090", it.accessKeyId, it.secretAccessKey)
        }
        val result = S3SigV4Presigner(portCfg).presign(
            httpMethod = "PUT",
            key = "resources/x.jpg",
            headers = linkedMapOf("content-type" to "image/jpeg"),
            expiresSeconds = 604800,
            timestamp = clock.instant,
        )
        assertTrue(result.canonicalRequest.contains("host:localhost:9090\n"), result.canonicalRequest)
        assertTrue(result.url.startsWith("http://localhost:9090/bucket/resources/x.jpg?"), result.url)
    }
}
