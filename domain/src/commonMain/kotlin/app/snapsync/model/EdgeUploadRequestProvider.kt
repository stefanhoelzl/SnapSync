package app.snapsync.model


/**
 * The production [UploadRequestProvider] (specs: sync-engine, bunny-upload-endpoint, device-attestation):
 * a thin **local URL builder** for the bunny edge proxy. It maps a [Resource] to a plain `PUT` against
 * `<host>/files/devices/<deviceId>/<encoded-filename>` — no network, no signing, no crypto — carrying
 * exactly two headers: `Content-Type` and the device token as `Authorization: Bearer`. No `Host`
 * (URL-implied) and **no custom metadata headers** (the bunny native Storage API has none). iOS's
 * background-upload job system performs the actual `PUT`; this only composes the request.
 *
 * **That the OS carries our `Authorization` header at all was measured, not assumed.** The extension hands
 * the request to the OS, which performs it on its own schedule — and until it was checked on a real device,
 * nothing proved an *arbitrary* header survived that handoff (the only header ever sent was `Content-Type`,
 * which the OS would set anyway). It does survive: the origin observed the header on a real photo `PUT`
 * whose `user-agent` was `assetsd`, the OS's own daemon. Had it not, no header-borne credential could gate
 * the byte route and the token would have had to move into the URL.
 *
 * The credential rides in the **header, never the URL**, which preserves the property the retry path
 * depends on: the URL is **stable with no expiry**, so a retry re-derived hours later re-`PUT`s a
 * byte-identical destination with nothing to re-mint.
 *
 * [token] is read on **every** call, never captured at construction. That is what heals an expired token:
 * the engine re-mints the request from this provider on each retry, so an upload that `401`ed on a dead
 * token simply succeeds once the app has renewed — with no special-casing anywhere in the upload path.
 *
 * A null token still yields a request. It will `401`, and a `401` is a retryable failure the engine already
 * handles; refusing to build a request would strand the resource instead. This is the normal state of a
 * device that has not attested yet, and of the upload extension on a device whose token expired — the
 * extension cannot renew (App Attest is unavailable in an app extension), so it sends what it has.
 *
 * The byte destination is **event-independent**: a resource is stored once under its device's partition
 * (`/files/devices/<deviceId>/`) and linked into any number of events by reference (the per-event device
 * manifest), so re-joining or switching events re-uploads nothing already stored.
 *
 * [host]/[deviceId] are plain strings, injected by the consuming composition root (host baked at compile
 * time, deviceId from the shared Keychain via the `device-identity` seam). The provider makes no platform
 * call.
 */
class EdgeUploadRequestProvider(
    host: String,
    private val deviceId: String,
    private val token: suspend () -> String? = { null },
) : UploadRequestProvider {

    // Trim a trailing slash so the baked host (with or without one) never yields `//files`.
    private val base = host.trimEnd('/')

    override suspend fun provide(resource: Resource): UploadRequest {
        val url = "$base/files/devices/$deviceId/${encodeFilenameSegment(resource.filename)}"
        val headers = buildMap {
            put("Content-Type", resource.contentType)
            token()?.let { put("Authorization", "Bearer $it") }
        }
        return UploadRequest(url = url, headers = headers, resource = resource)
    }
}
