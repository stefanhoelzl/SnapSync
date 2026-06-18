@file:OptIn(ExperimentalEncodingApi::class)

package app.snapsync.config

import app.snapsync.s3.S3Config
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigDeeplinkTest {

    private val sample = S3Config(
        bucket = "my-bucket",
        region = "eu-central-1",
        endpoint = "https://s3.eu-central-1.amazonaws.com",
        accessKeyId = "AKIAEXAMPLE",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
    )

    private fun success(raw: String): S3Config {
        val result = decodeConfigUrl(raw)
        assertTrue(result is ConfigDecodeResult.Success, "expected success, got $result")
        return result.config
    }

    private fun assertFailure(raw: String) {
        assertTrue(decodeConfigUrl(raw) is ConfigDecodeResult.Failure, "expected failure for: $raw")
    }

    @Test
    fun `encode then decode round-trips every field`() {
        val decoded = success(encodeConfigUrl(sample))
        assertEquals(sample.bucket, decoded.bucket)
        assertEquals(sample.region, decoded.region)
        assertEquals(sample.endpoint, decoded.endpoint)
        assertEquals(sample.accessKeyId, decoded.accessKeyId)
        assertEquals(sample.secretAccessKey, decoded.secretAccessKey)
    }

    @Test
    fun `encoded url has the canonical shape`() {
        assertTrue(encodeConfigUrl(sample).startsWith("snapsync://config?v=1&d="))
    }

    @Test
    fun `payload with optional padding still decodes`() {
        val padded = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT)
            .encode("""{"bucket":"b","region":"r","endpoint":"e","accessKeyId":"a","secretAccessKey":"s"}""".encodeToByteArray())
        success("snapsync://config?v=1&d=$padded")
    }

    @Test
    fun `wrong scheme or host fails`() {
        assertFailure("https://config?v=1&d=x")
        assertFailure("snapsync://setup?v=1&d=x")
        assertFailure("totally not a url")
    }

    @Test
    fun `wrong or missing version fails`() {
        val d = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode("""{"bucket":"b","region":"r","endpoint":"e","accessKeyId":"a","secretAccessKey":"s"}""".encodeToByteArray())
        assertFailure("snapsync://config?v=2&d=$d")
        assertFailure("snapsync://config?d=$d")
    }

    @Test
    fun `missing payload fails`() {
        assertFailure("snapsync://config?v=1")
    }

    @Test
    fun `undecodable base64url fails`() {
        assertFailure("snapsync://config?v=1&d=!!!not base64!!!")
    }

    @Test
    fun `non-json payload fails`() {
        val d = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("not json".encodeToByteArray())
        assertFailure("snapsync://config?v=1&d=$d")
    }

    @Test
    fun `missing key fails`() {
        val d = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode("""{"bucket":"b","region":"r","endpoint":"e","accessKeyId":"a"}""".encodeToByteArray())
        assertFailure("snapsync://config?v=1&d=$d")
    }

    @Test
    fun `empty field fails`() {
        val d = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode("""{"bucket":"","region":"r","endpoint":"e","accessKeyId":"a","secretAccessKey":"s"}""".encodeToByteArray())
        assertFailure("snapsync://config?v=1&d=$d")
    }

    @Test
    fun `unknown extra key fails`() {
        val d = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode("""{"bucket":"b","region":"r","endpoint":"e","accessKeyId":"a","secretAccessKey":"s","extra":"x"}""".encodeToByteArray())
        assertFailure("snapsync://config?v=1&d=$d")
    }
}
