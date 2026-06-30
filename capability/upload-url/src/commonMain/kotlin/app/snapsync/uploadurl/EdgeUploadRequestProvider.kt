package app.snapsync.uploadurl

import app.snapsync.engine.Resource
import app.snapsync.engine.UploadRequest
import app.snapsync.engine.UploadRequestProvider

/**
 * The production [UploadRequestProvider] (docs/design.md §2.2, §4): a thin **local URL builder** for
 * the credential-free bunny edge proxy. It maps a [Resource] to a plain `PUT` against
 * `<host>/files/device/<deviceId>/<encoded-filename>` — **no network, no signing, no crypto, no auth
 * header** (the byte route is ungated — see `bunny-upload-endpoint`) and **no custom metadata
 * headers** (the bunny native Storage API has none). iOS's background-upload job system performs the
 * actual `PUT`; this only composes the request.
 *
 * The byte destination is **event-independent**: a resource is stored once under its device's
 * partition (`/files/<deviceId>/`) and linked into any number of events by reference (the per-event
 * device manifest), so re-joining or switching events re-uploads nothing already stored.
 *
 * Placement and filename encoding live here, satisfying the seam's deterministic-and-injective
 * contract: `resource.filename → files/device/<deviceId>/<percent-encoded filename>`. The URL is
 * **stable with no expiry** — rebuilding for the same inputs yields a byte-identical URL, so a retry
 * re-derived much later re-PUTs the exact same destination (nothing to re-mint).
 *
 * [host]/[deviceId] are plain strings, injected by the consuming composition root (host baked at
 * compile time, deviceId from the shared Keychain via the `device-identity` seam). The provider makes
 * no platform call.
 */
class EdgeUploadRequestProvider(
    host: String,
    private val deviceId: String,
) : UploadRequestProvider {

    // Trim a trailing slash so the baked host (with or without one) never yields `//files`.
    private val base = host.trimEnd('/')

    override suspend fun provide(resource: Resource): UploadRequest {
        val url = "$base/files/device/$deviceId/${encodeFilenameSegment(resource.filename)}"
        val headers = mapOf("Content-Type" to resource.contentType)
        return UploadRequest(url = url, headers = headers, resource = resource)
    }
}
