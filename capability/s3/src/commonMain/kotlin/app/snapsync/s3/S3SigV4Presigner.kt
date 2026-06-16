package app.snapsync.s3

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256
import kotlin.time.Instant

/** The intermediate products of a presign — `url` is what callers want; the rest let tests localize a golden failure. */
internal class PresignResult(
    val url: String,
    val canonicalRequest: String,
    val stringToSign: String,
    val signature: String,
)

/**
 * Pure AWS Signature Version 4 **query-presigner** for S3 `PUT` (docs/design.md §3.3, §4): given an
 * already-encoded object key, the headers to sign, an expiry, and a timestamp, it produces a
 * presigned URL. Path-style addressing, `UNSIGNED-PAYLOAD`, service `s3`. No network, no I/O, no
 * `Resource` knowledge — the adapter above it owns that.
 *
 * The same single-encoded path string is used for both the wire URL and the canonical URI, which is
 * the keystone of a valid S3 signature (S3 is the documented single-encode exception).
 */
internal class S3SigV4Presigner(private val config: S3Config) {

    private val authority: String = config.endpoint.substringAfter("://").trimEnd('/')
    private val urlBase: String = config.endpoint.trimEnd('/')

    /**
     * @param key the already-encoded object key (e.g. `resources/AB%2Fc.jpg`), without bucket.
     * @param headers the headers to sign besides `host` — lowercase names (content-type, x-amz-meta-*).
     */
    fun presign(
        httpMethod: String,
        key: String,
        headers: Map<String, String>,
        expiresSeconds: Long,
        timestamp: Instant,
    ): PresignResult {
        val amzDate = amzDate(timestamp)
        val dateStamp = amzDate.substring(0, 8)
        val scope = "$dateStamp/${config.region}/$SERVICE/$TERMINATOR"

        // Full signed set = host + caller headers, sorted by (lowercase) name for canonicalization.
        val signed = sortedMapOf<String, String>().apply {
            put("host", authority)
            putAll(headers)
        }
        val signedHeaderNames = signed.keys.joinToString(";")
        val canonicalQuery = canonicalQuery(amzDate, scope, expiresSeconds, signedHeaderNames)
        val canonicalUri = "/${config.bucket}/$key"

        val canonicalHeaders = signed.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" }
        val canonicalRequest = buildString {
            append(httpMethod).append('\n')
            append(canonicalUri).append('\n')
            append(canonicalQuery).append('\n')
            append(canonicalHeaders).append('\n') // headers block already ends in \n; this adds the blank line
            append(signedHeaderNames).append('\n')
            append(UNSIGNED_PAYLOAD)
        }

        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(amzDate).append('\n')
            append(scope).append('\n')
            append(hex(SHA256().digest(canonicalRequest.encodeToByteArray())))
        }

        val signature = hex(signingKey(dateStamp).doFinalSign(stringToSign))
        val url = "$urlBase$canonicalUri?$canonicalQuery&$SIGNATURE_PARAM=$signature"
        return PresignResult(url, canonicalRequest, stringToSign, signature)
    }

    private fun canonicalQuery(
        amzDate: String,
        scope: String,
        expiresSeconds: Long,
        signedHeaderNames: String,
    ): String {
        val params = listOf(
            "X-Amz-Algorithm" to ALGORITHM,
            "X-Amz-Credential" to "${config.accessKeyId}/$scope",
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to expiresSeconds.toString(),
            "X-Amz-SignedHeaders" to signedHeaderNames,
        )
        return params
            .map { (k, v) -> encodeRfc3986(k) to encodeRfc3986(v) }
            .sortedBy { it.first }
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun amzDate(timestamp: Instant): String {
        val t = timestamp.toLocalDateTime(TimeZone.UTC)
        return t.year.pad(4) + (t.month.ordinal + 1).pad(2) + t.day.pad(2) +
            "T" + t.hour.pad(2) + t.minute.pad(2) + t.second.pad(2) + "Z"
    }

    private fun signingKey(dateStamp: String): ByteArray {
        val kDate = ("AWS4" + config.secretAccessKey).encodeToByteArray().doFinalSign(dateStamp)
        val kRegion = kDate.doFinalSign(config.region)
        val kService = kRegion.doFinalSign(SERVICE)
        return kService.doFinalSign(TERMINATOR)
    }

    /** HMAC-SHA256 of [data]'s UTF-8 bytes using this byte array as the key. */
    private fun ByteArray.doFinalSign(data: String): ByteArray =
        HmacSHA256(this).doFinal(data.encodeToByteArray())

    private fun Int.pad(width: Int): String = toString().padStart(width, '0')

    private companion object {
        const val ALGORITHM = "AWS4-HMAC-SHA256"
        const val SERVICE = "s3"
        const val TERMINATOR = "aws4_request"
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
        const val SIGNATURE_PARAM = "X-Amz-Signature"
    }
}
