package app.snapsync.services.backend

import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.Resource
import app.snapsync.model.UploadRequest
import app.snapsync.model.UploadRequestProvider
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.roleFromUploadKey
import app.snapsync.wire.api.Routes

/**
 * The production [UploadRequestProvider] (specs: sync-engine, bunny-upload-endpoint, device-attestation):
 * a thin **local URL builder** for the bunny edge proxy. It maps a [Resource] to a plain `PUT` against
 * the generated route `uploadResource` (`api/openapi.yaml`) — `<base>/events/<e>/files/devices/<d>/<asset>/<role>?filename=…`,
 * no network, no signing, no
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
 * The byte destination **belongs to one event** (change `per-event-storage-layout`): the backend stores it under
 * that event, so rejoining the event re-uploads nothing it already holds, while a photo that also falls inside
 * another event is uploaded again for that one. A request built by an earlier build — the event-less
 * `<host>/files/devices/<deviceId>/…` — still completes: the backend files it under the device's present event, and
 * the ledger matches its completion by the destination path it recorded when the request was made.
 *
 * The base and the declared version are the [BackendExchange]'s — the same every backend request carries; [eventId] and
 * [deviceId] come from the cycle's config and the shared Keychain. The provider makes no platform call.
 */
class BackendUploadRequests(
    private val exchange: BackendExchange,
    private val eventId: EventId,
    private val deviceId: DeviceId,
    private val token: suspend () -> String?,
    /**
     * The credential read from its store of record, bypassing any in-process copy — what [provideForRetry] uses.
     * Required, like [token]: a composition with no credential states `{ null }` for both.
     */
    private val freshToken: suspend () -> String?,
) : UploadRequestProvider {

    override suspend fun provide(resource: Resource): UploadRequest = mint(resource, token())

    override suspend fun provideForRetry(resource: Resource): UploadRequest = mint(resource, freshToken())

    private fun mint(resource: Resource, bearer: String?): UploadRequest {
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
        val url = exchange.url(
            Routes.UploadResource.path(eventId.value, deviceId.value, assetId.value, role, captureName),
        )
        val headers = buildMap {
            put("Content-Type", contentTypeOf(resource))
            putAll(exchange.versionHeaders())
            bearer?.let { put("Authorization", "Bearer $it") }
        }
        return UploadRequest(url = url, headers = headers, resource = resource)
    }

    /**
     * The **MIME type** for the `Content-Type` header — not [Resource.contentType], which on iOS is the
     * PhotoKit **UTI** (`public.jpeg`), a value no HTTP client, CDN or browser understands.
     *
     * The MIME rides in the resource's metadata, resolved iOS-side by `UTType.preferredMIMEType`
     * (`resourcesFrom`), and every other consumer already prefers it the same way — `toLedgerRow` does
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
