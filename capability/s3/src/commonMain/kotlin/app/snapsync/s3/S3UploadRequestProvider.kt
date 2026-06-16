package app.snapsync.s3

import app.snapsync.engine.Resource
import app.snapsync.engine.UploadRequest
import app.snapsync.engine.UploadRequestProvider
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * The production [UploadRequestProvider] (docs/design.md §2.2): maps a [Resource] to a presigned S3
 * `PUT` and delegates the cryptography to an internal [S3SigV4Presigner]. Pure string-building and
 * crypto — no network. iOS's job system performs the actual `PUT` against the minted URL.
 *
 * Encoding and placement of the filename live here, satisfying the seam's deterministic-and-injective
 * contract: `resource.filename → resources/<percent-encoded filename>`.
 *
 * [expiresIn] is the presign lifetime (default 7 days, the SigV4 maximum); the engine's `Retry` path
 * re-mints, so an expired URL self-heals. [clock] supplies the signing timestamp; inject a fixed
 * clock for deterministic output.
 */
class S3UploadRequestProvider(
    config: S3Config,
    expiresIn: Duration = 7.days,
    private val clock: Clock = Clock.System,
) : UploadRequestProvider {

    init {
        require(expiresIn > Duration.ZERO && expiresIn <= 7.days) {
            "expiresIn must be in (0, 7 days]; was $expiresIn"
        }
    }

    private val presigner = S3SigV4Presigner(config)
    private val expiresSeconds = expiresIn.inWholeSeconds

    override suspend fun provide(resource: Resource): UploadRequest {
        val key = KEY_PREFIX + encodeObjectKeySegment(resource.filename)
        val headers = headersFor(resource)
        val url = presigner.presign("PUT", key, headers, expiresSeconds, clock.now()).url
        return UploadRequest(url = url, headers = headers, resource = resource)
    }

    /**
     * Lowercase-keyed headers to sign and to send: `content-type` plus one `x-amz-meta-<lowercased
     * key>` per metadata entry, values verbatim. Names are lowercase so they correspond exactly to
     * the SigV4 canonical headers. `Host` is intentionally absent — it is URL-implied (though signed).
     */
    private fun headersFor(resource: Resource): Map<String, String> {
        val headers = LinkedHashMap<String, String>(resource.metadata.size + 1)
        headers["content-type"] = resource.contentType
        for ((key, value) in resource.metadata) {
            require(value.all { it.code in 0x20..0x7E }) {
                "metadata value for '$key' must be ASCII without control/CR/LF characters"
            }
            headers["x-amz-meta-" + key.lowercase()] = value
        }
        return headers
    }

    private companion object {
        const val KEY_PREFIX = "resources/"
    }
}
