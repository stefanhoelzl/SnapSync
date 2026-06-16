package app.snapsync.s3

import app.snapsync.engine.Resource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Golden / known-answer test — the sole signature guard for this slice (s3mock deferred). The
 * expected values are produced by an independent from-spec SigV4 implementation that is itself
 * verified against AWS's own published vector; regenerate with:
 *
 *   python3 openspec/changes/s3-request-provider/sigv4_reference.py
 *
 * (kept in the change folder). The reference reproduces AWS's documented `GET examplebucket/test.txt`
 * signature `aeeed9bb…`, so matching it here means our crypto matches AWS, not just itself.
 */
class S3PresignGoldenTest {

    private val config = S3Config(
        bucket = "snapsync-test",
        region = "eu-central-1",
        endpoint = "https://s3.eu-central-1.amazonaws.com",
        accessKeyId = "AKIAIOSFODNN7EXAMPLE",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    )
    private val timestamp = Instant.parse("2026-06-15T12:00:00Z")

    private val goldenSignature = "44f81602d8202caf8bbf3947895b8ebfe7da09ce16317da55fd5fe06be23824d"

    private val goldenCanonicalRequest = listOf(
        "PUT",
        "/snapsync-test/resources/AB%2Fcd-ios.photo.jpg",
        "X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20260615%2Feu-central-1%2Fs3%2Faws4_request" +
            "&X-Amz-Date=20260615T120000Z&X-Amz-Expires=604800" +
            "&X-Amz-SignedHeaders=content-type%3Bhost%3Bx-amz-meta-asset-id%3Bx-amz-meta-original-filename",
        "content-type:image/jpeg",
        "host:s3.eu-central-1.amazonaws.com",
        "x-amz-meta-asset-id:ABC123",
        "x-amz-meta-original-filename:IMG_0001.HEIC",
        "",
        "content-type;host;x-amz-meta-asset-id;x-amz-meta-original-filename",
        "UNSIGNED-PAYLOAD",
    ).joinToString("\n")

    private val goldenStringToSign = listOf(
        "AWS4-HMAC-SHA256",
        "20260615T120000Z",
        "20260615/eu-central-1/s3/aws4_request",
        "b9522f1b208f9c18845bff8745ff399a12cd11beb1f97f60660c3516d889bca4",
    ).joinToString("\n")

    private val goldenUrl =
        "https://s3.eu-central-1.amazonaws.com/snapsync-test/resources/AB%2Fcd-ios.photo.jpg" +
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20260615%2Feu-central-1%2Fs3%2Faws4_request" +
            "&X-Amz-Date=20260615T120000Z&X-Amz-Expires=604800" +
            "&X-Amz-SignedHeaders=content-type%3Bhost%3Bx-amz-meta-asset-id%3Bx-amz-meta-original-filename" +
            "&X-Amz-Signature=$goldenSignature"

    /** Localized asserts on the core, so a failure points at the exact SigV4 stage that broke. */
    @Test
    fun `core reproduces the independent reference, stage by stage`() {
        val result = S3SigV4Presigner(config).presign(
            httpMethod = "PUT",
            key = "resources/AB%2Fcd-ios.photo.jpg",
            headers = linkedMapOf(
                "content-type" to "image/jpeg",
                "x-amz-meta-asset-id" to "ABC123",
                "x-amz-meta-original-filename" to "IMG_0001.HEIC",
            ),
            expiresSeconds = 604800,
            timestamp = timestamp,
        )
        assertEquals(goldenCanonicalRequest, result.canonicalRequest)
        assertEquals(goldenStringToSign, result.stringToSign)
        assertEquals(goldenSignature, result.signature)
        assertEquals(goldenUrl, result.url)
    }

    /** End-to-end through the provider: a Resource whose filename encodes to the golden key. */
    @Test
    fun `provider mints the golden url`() = runTest {
        val provider = S3UploadRequestProvider(config, clock = FixedClock(timestamp))
        val resource = Resource(
            filename = "AB/cd-ios.photo.jpg",
            contentType = "image/jpeg",
            version = "v1",
            metadata = mapOf("asset-id" to "ABC123", "original-filename" to "IMG_0001.HEIC"),
            data = Unit,
        )
        assertEquals(goldenUrl, provider.provide(resource).url)
    }
}
