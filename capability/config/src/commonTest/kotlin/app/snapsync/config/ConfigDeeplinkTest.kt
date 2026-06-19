@file:OptIn(ExperimentalEncodingApi::class)

package app.snapsync.config

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigDeeplinkTest {

    private val sample = S3ConfigPayload(
        bucket = "my-bucket",
        region = "eu-central-1",
        accessKeyId = "AKIAEXAMPLE",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
    )

    private fun success(raw: String): S3ConfigPayload {
        val result = decodeConfigUrl(raw)
        assertTrue(result is ConfigDecodeResult.Success, "expected success, got $result")
        return result.payload
    }

    private fun assertFailure(raw: String) {
        assertTrue(decodeConfigUrl(raw) is ConfigDecodeResult.Failure, "expected failure for: $raw")
    }

    private fun absent(json: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())

    @Test
    fun `encode then decode round-trips every field`() {
        val decoded = success(encodeConfigUrl(sample))
        assertEquals(sample.bucket, decoded.bucket)
        assertEquals(sample.region, decoded.region)
        assertEquals(sample.accessKeyId, decoded.accessKeyId)
        assertEquals(sample.secretAccessKey, decoded.secretAccessKey)
    }

    @Test
    fun `encoded url has the canonical shape`() {
        assertTrue(encodeConfigUrl(sample).startsWith("snapsync://config?v=2&d="))
    }

    @Test
    fun `payload with optional padding still decodes`() {
        val padded = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT)
            .encode("""{"bucket":"b","region":"r","accessKeyId":"a","secretAccessKey":"s"}""".encodeToByteArray())
        success("snapsync://config?v=2&d=$padded")
    }

    @Test
    fun `wrong scheme or host fails`() {
        assertFailure("https://config?v=2&d=x")
        assertFailure("snapsync://setup?v=2&d=x")
        assertFailure("totally not a url")
    }

    @Test
    fun `wrong or missing version fails`() {
        val d = absent("""{"bucket":"b","region":"r","accessKeyId":"a","secretAccessKey":"s"}""")
        // The old four-field-less v=1 contract is rejected, not silently mis-read.
        assertFailure("snapsync://config?v=1&d=$d")
        assertFailure("snapsync://config?v=3&d=$d")
        assertFailure("snapsync://config?d=$d")
    }

    @Test
    fun `missing payload fails`() {
        assertFailure("snapsync://config?v=2")
    }

    @Test
    fun `undecodable base64url fails`() {
        assertFailure("snapsync://config?v=2&d=!!!not base64!!!")
    }

    @Test
    fun `non-json payload fails`() {
        assertFailure("snapsync://config?v=2&d=${absent("not json")}")
    }

    @Test
    fun `missing key fails`() {
        assertFailure("snapsync://config?v=2&d=${absent("""{"bucket":"b","region":"r","accessKeyId":"a"}""")}")
    }

    @Test
    fun `empty field fails`() {
        assertFailure("snapsync://config?v=2&d=${absent("""{"bucket":"","region":"r","accessKeyId":"a","secretAccessKey":"s"}""")}")
    }

    @Test
    fun `unknown extra key fails`() {
        assertFailure("snapsync://config?v=2&d=${absent("""{"bucket":"b","region":"r","accessKeyId":"a","secretAccessKey":"s","extra":"x"}""")}")
    }

    @Test
    fun `stray endpoint key fails`() {
        // A stale host-carrying payload (the v=1 shape) must be rejected: the host is compile-time now.
        assertFailure("snapsync://config?v=2&d=${absent("""{"bucket":"b","region":"r","endpoint":"e","accessKeyId":"a","secretAccessKey":"s"}""")}")
    }
}
