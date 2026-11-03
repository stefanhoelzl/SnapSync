package app.snapsync.data.upload

import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.TokenRead
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.DestinationPath
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadKey
import app.snapsync.format.ResourceRoleText
import app.snapsync.format.UploadKeyText
import app.snapsync.wire.api.Routes

/**
 * **The upload of one resource, as the backend's byte route takes it** — the destination and the headers a transfer
 * tier hands the platform. The destination belongs to one event and names this device, the asset and the role in its
 * path (change `per-event-storage-layout`), so a key's bytes land under the same object name however often they are
 * sent; the photo's original file name rides in the query, of which the backend reads only the extension — the key's
 * own when the row records no name.
 *
 * Carrying `Content-Type` (the MIME the walk recorded), the calling build's version headers, and the device's token as
 * `Authorization: Bearer` when one is held. The token is this process's copy for a first upload and the store of
 * record's for a [retry] — the failure may have been the `401` of a token the other process has since renewed.
 */
class UploadRequests(
    private val exchange: BackendExchange,
    private val credentials: CachedCredentialStore,
    private val identity: DeviceIdentity,
) {
    /** [key]'s first upload into [event], described by [detail]. */
    fun first(event: EventId, key: UploadKey, detail: RowDetail): RequestBuilt = built(event, key, detail)

    /** [key]'s upload again, after a failure: the token read from its store of record first. */
    fun retry(event: EventId, key: UploadKey, detail: RowDetail): RequestBuilt {
        credentials.reread()
        return built(event, key, detail)
    }

    private fun built(event: EventId, key: UploadKey, detail: RowDetail): RequestBuilt = when (val id = identity.id()) {
        is DeviceIdRead.Ok -> RequestBuilt.Built(request(event, id.value, key, detail, credentials.token()))
        DeviceIdRead.Absent -> RequestBuilt.NoIdentity
        is DeviceIdRead.Unavailable -> RequestBuilt.IdentityUnreadable(id.detail)
    }

    private fun request(
        event: EventId,
        device: DeviceId,
        key: UploadKey,
        detail: RowDetail,
        token: TokenRead,
    ): BuiltRequest {
        val path = Routes.UploadResource.path(
            event.value,
            device.value,
            key.asset.value,
            ResourceRoleText.of(key.role),
            detail.originalFilename.ifBlank { UploadKeyText.of(key) },
        )
        val url = exchange.url(path)
        val bearer = token.held()?.let { mapOf("Authorization" to "Bearer ${it.secret}") }.orEmpty()
        val headers = mapOf("Content-Type" to detail.contentType) + exchange.versionHeaders() + bearer
        return BuiltRequest(url, headers, Destinations.pathOf(url))
    }
}

/** What building an upload's request came to. */
sealed interface RequestBuilt {
    class Built(val request: BuiltRequest) : RequestBuilt

    /** This process holds no device id and may not mint one: nothing can be addressed to this device. */
    data object NoIdentity : RequestBuilt

    /** The device id could not be read now (a locked device). [detail] is diagnostics only. */
    data class IdentityUnreadable(val detail: String) : RequestBuilt
}

/** One upload's [url] and [headers], and the [destination] path its row records when its job is accepted. */
class BuiltRequest(val url: String, val headers: Map<String, String>, val destination: DestinationPath)
