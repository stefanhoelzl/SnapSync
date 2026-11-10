package app.snapsync.data.upload

import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.Credential
import app.snapsync.data.backend.EntryPointLabel
import app.snapsync.data.backend.TokenRead
import app.snapsync.data.crypto.FileNameCipher
import app.snapsync.data.crypto.UploadSeal
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.time.WallClock
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.trust.BearerToken
import app.snapsync.domain.upload.DestinationPath
import app.snapsync.domain.upload.Discovery
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadKey
import app.snapsync.format.ResourceRoleText
import app.snapsync.format.TimestampText
import app.snapsync.format.UploadKeyText
import app.snapsync.wire.api.Routes

/**
 * **The upload of one resource, as the backend's byte route takes it** — the destination and the headers a transfer
 * tier hands the platform. The destination belongs to one event and names this device, the asset and the role in its
 * path (change `per-event-storage-layout`), so a key's bytes land under the same object name however often they are
 * sent; the photo's original file name rides in the query — the key's own text when the row records no name — and in
 * an encrypted event only sealed for that photo, so the backend never reads it.
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
    private val names: FileNameCipher,
    private val clock: WallClock,
) {
    /**
     * [key]'s first upload into [event], described by [detail] — as its event's [seal] says: an encrypted event's
     * seals the photo's name with its bytes.
     */
    suspend fun first(event: EventId, key: UploadKey, detail: RowDetail, seal: UploadSeal.Goes): RequestBuilt =
        built(Upload(event, key, detail, seal))

    /** [key]'s upload again, after a failure: the token read from its store of record first. */
    suspend fun retry(event: EventId, key: UploadKey, detail: RowDetail, seal: UploadSeal.Goes): RequestBuilt {
        credentials.reread()
        return built(Upload(event, key, detail, seal))
    }

    private suspend fun built(upload: Upload): RequestBuilt = when (val id = identity.id()) {
        is DeviceIdRead.Ok -> proven(upload, id.value)
        DeviceIdRead.Absent -> RequestBuilt.NoIdentity
        is DeviceIdRead.Unavailable -> RequestBuilt.IdentityUnreadable(id.detail)
    }

    private suspend fun proven(upload: Upload, device: DeviceId): RequestBuilt = when (val token = gated()) {
        is TokenRead.Held -> RequestBuilt.Built(request(upload, device, token.token))
        TokenRead.None -> RequestBuilt.Unverified("no proof is held, and this process could obtain none")
        is TokenRead.Unavailable -> RequestBuilt.Unverified(token.detail)
    }

    /** The token, the gated way: the one held, or one this process obtains first. */
    private suspend fun gated(): TokenRead = when (val read = credential.token()) {
        TokenRead.None -> credential.missing()
        is TokenRead.Held, is TokenRead.Unavailable -> read
    }

    private suspend fun request(upload: Upload, device: DeviceId, token: BearerToken): BuiltRequest {
        val key = upload.key
        val path = Routes.UploadResource.path(
            upload.event.value,
            device.value,
            key.asset.value,
            ResourceRoleText.of(key.role),
            nameOf(upload),
        )
        val url = exchange.url(path)
        val headers = mapOf("Content-Type" to upload.detail.contentType) + exchange.transferHeaders() +
            delivery(upload.detail) + mapOf("Authorization" to "Bearer ${token.secret}")
        return BuiltRequest(url, headers, Destinations.pathOf(url), device)
    }

    /**
     * The name the upload's query carries: the photo's own — or the key's when its row records none — readable in a
     * plain event, and in an encrypted one only sealed for this photo (decision record `changes/seal-file-names`).
     */
    private fun nameOf(upload: Upload): String {
        val name = upload.detail.originalFilename.ifBlank { UploadKeyText.of(upload.key) }
        return when (val seal = upload.seal) {
            UploadSeal.Plain -> name
            is UploadSeal.Sealed -> names.sealFileName(seal.order.key, seal.order.binding, name)
        }
    }

    /** One upload's request, as its tier asks for it. */
    private class Upload(val event: EventId, val key: UploadKey, val detail: RowDetail, val seal: UploadSeal.Goes)

    /**
     * The delivery record's facts of this upload (decision record `changes/delivery-record`, D3): when the photo was
     * taken, when the app found it and why, and when this upload is queued and why. A background transfer's headers are
     * fixed now, so `queued` is the moment it is handed over by construction.
     */
    private suspend fun delivery(detail: RowDetail): Map<String, String> =
        taken(detail.captured) + discovered(detail.discovered) +
            mapOf(Routes.UploadResource.HEADER_X_SNAPSYNC_QUEUED to TimestampText.format(clock.now())) +
            EntryPointLabel.current()?.let { mapOf(Routes.UploadResource.HEADER_X_SNAPSYNC_QUEUED_BY to it) }.orEmpty()

    private fun taken(captured: CaptureDate): Map<String, String> = when (captured) {
        is CaptureDate.Dated -> mapOf(
            Routes.UploadResource.HEADER_X_SNAPSYNC_TAKEN to TimestampText.format(captured.at),
        )
        CaptureDate.Undated -> emptyMap()
    }

    private fun discovered(discovery: Discovery?): Map<String, String> = discovery?.let {
        mapOf(Routes.UploadResource.HEADER_X_SNAPSYNC_DISCOVERED to TimestampText.format(it.at)) +
            it.by?.let { by -> mapOf(Routes.UploadResource.HEADER_X_SNAPSYNC_DISCOVERED_BY to by) }.orEmpty()
    }.orEmpty()
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
