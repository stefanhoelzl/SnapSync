package app.snapsync.data.upload

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.ResumePoint
import app.snapsync.domain.upload.UploadKey
import app.snapsync.format.ResourceRoleText
import app.snapsync.format.UploadKeyText
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.wire.api.AppTooOld
import app.snapsync.wire.api.Invalid
import app.snapsync.wire.api.Maintenance
import app.snapsync.wire.api.Malformed
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerError
import app.snapsync.wire.api.Unauthorized
import app.snapsync.wire.api.Unreachable
import app.snapsync.wire.api.UploadStateReply

/**
 * **Where a failed upload stands** (`docs/architecture.md`, "Resumable uploads"): asked of the backend before the same
 * file is sent again, so the retry goes on where the last attempt stopped rather than at byte 0 — on a slow link each
 * attempt dies after a minute, and restarting never finishes a large video (Bugsink SNAPSYNC-54).
 *
 * The backend is asked, never the device's own count: after a network cut that count runs ahead of what arrived by the
 * platform's whole send buffer (0.7–1.1 MB, measured at every speed). An answer that is not about these bytes sends the
 * file whole, as it always went; a question that cannot be asked — no network, no readable device id — defers.
 */
class UploadStandings(private val gateway: BackendGateway, private val identity: DeviceIdentity) {
    private val log = Log("UploadStandings")

    /** Where [key]'s file in [event], whose bytes have the SHA-256 [sha256], goes on from. */
    suspend fun of(event: EventId, key: UploadKey, sha256: String): ResumePoint = when (val id = identity.id()) {
        is DeviceIdRead.Ok -> asked(event, id.value, key, sha256)
        DeviceIdRead.Absent -> ResumePoint.From(0)
        is DeviceIdRead.Unavailable -> deferred(key, id.detail)
    }

    private suspend fun asked(event: EventId, device: DeviceId, key: UploadKey, sha256: String): ResumePoint {
        val request = Routes.UploadState.request(
            event.value,
            device.value,
            key.asset.value,
            ResourceRoleText.of(key.role),
            xSnapsyncSha256 = sha256,
        )
        return point(gateway.send(request), key)
    }

    private suspend fun point(reply: UploadStateReply, key: UploadKey): ResumePoint = when (reply) {
        UploadStateReply.Landed -> ResumePoint.Landed
        is UploadStateReply.Stored -> ResumePoint.From(reply.uploadOffset.toLongOrNull() ?: 0)
        // Nothing stored for these bytes: the route's own answer.
        UploadStateReply.NothingStored -> ResumePoint.From(0)
        // The network that would carry the rest cannot carry the question either: no transfer now, the next pass asks.
        is Unreachable -> ResumePoint.Deferred
        // Any refusal: the file goes whole, as it always did — never withheld on an answer that is not about it.
        UploadStateReply.Forbidden, Unauthorized, is Invalid, is AppTooOld, is ServerError, is Maintenance,
        is Malformed,
        -> whole(key, reply)
    }

    private suspend fun whole(key: UploadKey, reply: UploadStateReply): ResumePoint {
        log.w(
            "upload state answered no standing — sending the file whole",
            "key" of UploadKeyText.of(key),
            "reply" of reply,
        )
        return ResumePoint.From(0)
    }

    private suspend fun deferred(key: UploadKey, detail: String): ResumePoint {
        log.i("no device id to ask with — deferred", "key" of UploadKeyText.of(key), "detail" of detail)
        return ResumePoint.Deferred
    }
}
