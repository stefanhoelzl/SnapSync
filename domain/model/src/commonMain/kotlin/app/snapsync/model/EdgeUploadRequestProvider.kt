package app.snapsync.model


/**
 * The production [UploadRequestProvider] (specs: sync-engine, bunny-upload-endpoint, device-attestation):
 * a thin **local URL builder** for the bunny edge proxy. It maps a [Resource] to a plain `PUT` against
 * `<host>/files/devices/<deviceId>/<assetId>/<role>?filename=<capture name>` — no network, no signing, no
 * crypto — carrying `Content-Type`, the device token as `Authorization: Bearer`, and the calling build's
 * marketing version. No `Host` (URL-implied) and **no custom metadata headers** (the bunny native Storage
 * API has none). iOS's background-upload job system performs the actual `PUT`; this only composes the
 * request.
 *
 * **That the OS carries our headers at all was measured, not assumed.** The extension hands the request to
 * the OS, which performs it on its own schedule. Measured on device (SE2 / iOS 26.6, 2026-08-28): the
 * origin observed a genuinely bespoke `x-snapsync-*` header on a real photo `PUT` whose `user-agent` was
 * `assetsd`, the OS's own daemon — and the same header read back out of the job's stored destination on
 * the acknowledge path. The earlier note claimed this for an *arbitrary* header on the strength of
 * `Authorization` alone, which is a standard header the OS keeps for its own reasons; it is now measured
 * for one of ours. Had it not survived, neither a header-borne credential nor the version declaration
 * could ride here, and both would have had to move into the URL.
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
 * The stored object name this destination resolves to is **byte-identical** to the one the previous byte
 * route composed for the same resource, so a device crossing versions finds its bytes where it left them
 * and re-uploads nothing.
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
    /**
     * The calling build's marketing version, declared on every v2 request (capability
     * `min-app-version`).
     *
     * Required HERE and not only on the shared HTTP client because **the OS performs this request**: it
     * is handed to the platform's background-upload subsystem and issued later, outside any client this
     * app controls, so a header that client adds cannot reach it. Injected as a plain string because the
     * provider is platform-free and must make no platform call.
     */
    private val appVersion: String = "",
) : UploadRequestProvider {

    // Trim a trailing slash so the baked host (with or without one) never yields `//files`.
    private val base = host.trimEnd('/')

    override suspend fun provide(resource: Resource): UploadRequest {
        // Identity in the PATH, capture name in a required QUERY. `assetId` and `role` are derived from
        // the ledger key through the shared parsers, so the one definition of that layout stays in
        // `model/` and the destination cannot disagree with the row it belongs to.
        val assetId = assetIdFromUploadKey(resource.filename)
        val role = roleFromUploadKey(resource.filename).wire
        // The capture name falls back to the KEY, and the fallback is exact rather than approximate: the
        // endpoint consumes only this value's EXTENSION when composing the stored object name, and the
        // key carries the same extension as the capture name it was built from. That is what lets a
        // request rebuilt on the retry path — where metadata is empty — address a byte-identical object.
        val captureName = resource.metadata[RESOURCE_META_ORIGINAL_FILENAME]
            ?.takeIf { it.isNotBlank() } ?: resource.filename
        val url = "$base/files/devices/$deviceId/" +
            "${encodeFilenameSegment(assetId)}/${encodeFilenameSegment(role)}" +
            "?filename=${encodeFilenameSegment(captureName)}"
        val headers = buildMap {
            put("Content-Type", contentTypeOf(resource))
            if (appVersion.isNotBlank()) put(APP_VERSION_HEADER, appVersion)
            token()?.let { put("Authorization", "Bearer $it") }
        }
        return UploadRequest(url = url, headers = headers, resource = resource)
    }

    /**
     * The **MIME type** for the `Content-Type` header — not [Resource.contentType], which on iOS is the
     * PhotoKit **UTI** (`public.jpeg`), a value no HTTP client, CDN or browser understands.
     *
     * The MIME rides in the resource's metadata, resolved iOS-side by `UTType.preferredMIMEType`
     * (`RawAssetMapping`), and every other consumer already prefers it the same way — `toLedgerRow` does
     * `metadata[RESOURCE_META_MIME] ?: contentType`, so the device manifest and the event union have
     * always carried the MIME. Only this header did not, so every stored object was typed with a UTI.
     * Measured on device 2026-08-07: `content-type: public.jpeg` at the origin.
     *
     * The fallback is [Resource.contentType] and is load-bearing for the **retry** path, where the cycle
     * rebuilds a `Resource` from the job key alone with empty metadata: the platform recovers the type
     * from the job's stored destination header (which this method wrote), so the value round-trips
     * instead of degrading.
     */
    private fun contentTypeOf(resource: Resource): String =
        resource.metadata[RESOURCE_META_MIME]?.takeIf { it.isNotBlank() } ?: resource.contentType
}
