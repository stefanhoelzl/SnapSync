package app.snapsync.uploadurl

import app.snapsync.engine.Resource
import app.snapsync.engine.UploadRequest
import app.snapsync.engine.UploadRequestProvider

/**
 * The production [UploadRequestProvider] (docs/design.md §2.2, §4): a thin **local URL builder** for
 * the credential-free bunny edge proxy. It maps a [Resource] to a plain `PUT` against
 * `<host>/event/<eventId>/device/<deviceId>/file/<encoded-filename>` — **no network, no signing, no
 * crypto, no auth header** (the edge endpoint authorizes by the `eventId` in the path) and **no
 * custom metadata headers** (the bunny native Storage API has none). iOS's background-upload job
 * system performs the actual `PUT`; this only composes the request.
 *
 * Placement and filename encoding live here, satisfying the seam's deterministic-and-injective
 * contract: `resource.filename → <eventId>/<deviceId>/<percent-encoded filename>`. The URL is
 * **stable with no expiry** — rebuilding for the same inputs yields a byte-identical URL, so a retry
 * re-derived much later re-PUTs the exact same destination (nothing to re-mint).
 *
 * [host]/[eventId]/[deviceId] are plain strings, injected by the consuming composition root (host
 * baked at compile time, eventId from the Keychain, deviceId from the App Group). The provider makes
 * no platform call.
 */
class EdgeUploadRequestProvider(
    host: String,
    private val eventId: String,
    private val deviceId: String,
) : UploadRequestProvider {

    // Trim a trailing slash so the baked host (with or without one) never yields `//event`.
    private val base = host.trimEnd('/')

    override suspend fun provide(resource: Resource): UploadRequest {
        val url = "$base/event/$eventId/device/$deviceId/file/${encodeFilenameSegment(resource.filename)}"
        val headers = mapOf("Content-Type" to resource.contentType)
        return UploadRequest(url = url, headers = headers, resource = resource)
    }
}
