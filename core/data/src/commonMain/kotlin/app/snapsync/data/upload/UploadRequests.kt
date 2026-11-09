package app.snapsync.data.upload

import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.Credential
import app.snapsync.data.backend.TokenRead
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.trust.BearerToken
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
 * `Authorization: Bearer`. An upload is a GATED request (change `withhold-unverified-uploads`): it is built only with a
 * token — held, or obtained first where this process can ([Credential.missing]: the app attests; the upload extension
 * cannot) — and never without one: a photo's bytes must not leave the phone on a request the backend can only refuse,
 * so a phone that holds no proof answers [RequestBuilt.Unverified] and the photo waits for the next verified pass. A
 * held token is sent whether or not the backend will judge it expired — the backend is the one judge of a proof. The
 * token is this process's copy for a first upload and the store of record's for a [retry] — the failure may have been
 * the `401` of a token the other process has since renewed.
 */
class UploadRequests(
    private val exchange: BackendExchange,
    private val credentials: CachedCredentialStore,
    private val credential: Credential,
    private val identity: DeviceIdentity,
) {
    /** [key]'s first upload into [event], described by [detail]. */
    suspend fun first(event: EventId, key: UploadKey, detail: RowDetail): RequestBuilt = built(event, key, detail)

    /** [key]'s upload again, after a failure: the token read from its store of record first. */
    suspend fun retry(event: EventId, key: UploadKey, detail: RowDetail): RequestBuilt {
        credentials.reread()
        return built(event, key, detail)
    }

    private suspend fun built(event: EventId, key: UploadKey, detail: RowDetail): RequestBuilt =
        when (val id = identity.id()) {
            is DeviceIdRead.Ok -> proven(event, id.value, key, detail)
            DeviceIdRead.Absent -> RequestBuilt.NoIdentity
            is DeviceIdRead.Unavailable -> RequestBuilt.IdentityUnreadable(id.detail)
        }

    private suspend fun proven(event: EventId, device: DeviceId, key: UploadKey, detail: RowDetail): RequestBuilt =
        when (val token = gated()) {
            is TokenRead.Held -> RequestBuilt.Built(request(event, device, key, detail, token.token))
            TokenRead.None -> RequestBuilt.Unverified("no proof is held, and this process could obtain none")
            is TokenRead.Unavailable -> RequestBuilt.Unverified(token.detail)
        }

    /** The token, the gated way: the one held, or one this process obtains first. */
    private suspend fun gated(): TokenRead = when (val read = credential.token()) {
        TokenRead.None -> credential.missing()
        is TokenRead.Held, is TokenRead.Unavailable -> read
    }

    private fun request(
        event: EventId,
        device: DeviceId,
        key: UploadKey,
        detail: RowDetail,
        token: BearerToken,
    ): BuiltRequest {
        val path = Routes.UploadResource.path(
            event.value,
            device.value,
            key.asset.value,
            ResourceRoleText.of(key.role),
            detail.originalFilename.ifBlank { UploadKeyText.of(key) },
        )
        val url = exchange.url(path)
        val headers = mapOf("Content-Type" to detail.contentType) + exchange.versionHeaders() +
            mapOf("Authorization" to "Bearer ${token.secret}")
        return BuiltRequest(url, headers, Destinations.pathOf(url), device)
    }
}

/** What building an upload's request came to. */
sealed interface RequestBuilt {
    class Built(val request: BuiltRequest) : RequestBuilt

    /** This process holds no device id and may not mint one: nothing can be addressed to this device. */
    data object NoIdentity : RequestBuilt

    /** The device id could not be read now (a locked device). [detail] is diagnostics only. */
    data class IdentityUnreadable(val detail: String) : RequestBuilt

    /**
     * This phone holds no proof it is a genuine SnapSync app, and could obtain none now — or its proof could not be
     * read. Nothing is sent: the photo waits on the phone until it is verified. [detail] is diagnostics only.
     */
    data class Unverified(val detail: String) : RequestBuilt
}

/**
 * One upload's [url] and [headers], the [destination] path its row records when its job is accepted, and the [device]
 * it was built as — the id its path names, so a later question about these bytes asks as the same device.
 */
class BuiltRequest(
    val url: String,
    val headers: Map<String, String>,
    val destination: DestinationPath,
    val device: DeviceId,
)
