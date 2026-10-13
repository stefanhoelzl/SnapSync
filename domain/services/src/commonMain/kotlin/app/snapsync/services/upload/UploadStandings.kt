package app.snapsync.services.upload

import app.snapsync.domain.event.EventId
import app.snapsync.model.DeviceIdentityAbsent
import app.snapsync.model.Resource
import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.roleFromUploadKey
import app.snapsync.services.backend.BackendGateway
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.Unreachable
import app.snapsync.wire.api.UploadStateReply
import co.touchlab.kermit.Logger

/**
 * **Where a failed upload stands** (`docs/architecture.md`, "Resumable uploads"): asked of the backend before the same
 * file is sent again, so the retry continues where the last attempt stopped rather than at byte 0 — on a slow link
 * each attempt dies after a minute, and restarting never finishes a large video (Bugsink SNAPSYNC-54).
 *
 * The backend is asked, never the device's own count: after a network cut that count runs ahead of what arrived by
 * the platform's whole send buffer (0.7–1.1 MB, measured at every speed), about one attempt's worth on the links that
 * need this.
 */
class UploadStandings(
    private val gateway: BackendGateway,
    /** The event this device is joined to, or `null` when it is not. */
    private val joinedEvent: () -> EventId?,
    private val identity: PersistedDeviceIdentity,
    private val log: Logger = Logger.withTag("UploadStandings"),
) : Standings {

    /** Where to send [resource]'s file from, whose bytes have the SHA-256 [sha256]. */
    override suspend fun of(resource: Resource, sha256: String): ResumePoint {
        val eventId = joinedEvent() ?: return ResumePoint.From(0)
        val deviceId = try {
            identity.deviceId()
        } catch (e: SecureStoreUnavailable) {
            log.i { "${resource.filename}: no device id to ask with (${e.message}) — deferred" }
            return ResumePoint.Deferred
        } catch (_: DeviceIdentityAbsent) {
            return ResumePoint.From(0)
        }
        val request = Routes.UploadState.request(
            eventId.value,
            deviceId.value,
            assetIdFromUploadKey(resource.filename).value,
            roleFromUploadKey(resource.filename).wire,
            xSnapsyncSha256 = sha256,
        )
        return when (val reply = gateway.send(request)) {
            UploadStateReply.Landed -> ResumePoint.Landed
            is UploadStateReply.Stored -> reply.uploadOffset.toLongOrNull()?.let(ResumePoint::From) ?: ResumePoint.From(0)
            // Nothing stored for these bytes: the route's own answer.
            UploadStateReply.NothingStored -> ResumePoint.From(0)
            // The network that would carry the rest cannot carry the question either: no transfer now, the next cycle asks.
            is Unreachable -> ResumePoint.Deferred
            // Any refusal: the file goes whole, as it always did — never withheld on an answer that is not about it.
            else -> ResumePoint.From(
                0,
            ).also { log.w { "${resource.filename}: upload state answered $reply — sending it whole" } }
        }
    }
}

/** Where a failed upload's file goes on from. */
sealed interface ResumePoint {
    /** The backend already holds these bytes whole — the last attempt's answer was lost on the way back. */
    data object Landed : ResumePoint

    /** Send from byte [offset]: `0` sends the file whole. */
    data class From(val offset: Long) : ResumePoint

    /** The backend could not be asked: create nothing now. */
    data object Deferred : ResumePoint
}
