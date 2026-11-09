package app.snapsync.data.sharing

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ServiceFaults
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.event.EventId
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.DeviceManifest
import app.snapsync.domain.sharing.ManifestAsset
import app.snapsync.domain.sharing.ManifestResource
import app.snapsync.domain.sharing.ShareDeclaration
import app.snapsync.format.TimestampText
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.Closed
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.PublishManifestReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerIssue
import app.snapsync.wire.models.Manifest
import app.snapsync.wire.models.Role
import app.snapsync.wire.runtime.WireJson
import app.snapsync.wire.models.ManifestAsset as WireAsset
import app.snapsync.wire.models.ManifestResource as WireResource

/**
 * **This device's manifest, told to the backend** (`publishManifest`): the full-state replace of what it declares to an
 * event. It enrols nobody — a device holding no membership is refused rather than given one — and records no upload.
 * The body is written once ([ManifestBody.of]) and sent as written, so the record holds exactly what the backend took.
 */
class ManifestPublication(private val gateway: BackendGateway) {

    /** Publish [body] for [manifest]'s device in [event]. */
    suspend fun publish(event: EventId, manifest: DeviceManifest, body: ManifestBody): PublishReply =
        when (val reply = gateway.send(Routes.PublishManifest.request(event.value, manifest.device.value, body.wire))) {
            PublishManifestReply.Published -> PublishReply.Published
            // The service's positive word that the event's photos were deleted.
            is Closed -> PublishReply.Refused(PublishRefusal.EVENT_OVER)
            // An event closed before events stopped closing keeps its set: re-sending could never change the answer.
            is PublishManifestReply.SetFixed -> PublishReply.Refused(PublishRefusal.SET_FIXED)
            is ClientError -> PublishReply.Refused(PublishRefusal.NOT_TAKEN)
            is ServerIssue, is NoAnswer -> PublishReply.Unreachable(ServiceFaults.of(reply))
        }
}

/** A manifest as the backend takes it: the [wire] body and its [text], which the record compares. */
class ManifestBody private constructor(internal val wire: Manifest, val text: String) {
    companion object {
        /** [manifest] as the backend takes it: the evidence of a settled share crosses the wire only as `final`. */
        fun of(manifest: DeviceManifest): ManifestBody {
            val wire = Manifest(
                deviceId = manifest.device.value,
                assets = manifest.assets.map(::wireAsset),
                version = manifest.version.value,
                `final` = settled(manifest.declaration),
            )
            return ManifestBody(wire, WireJson.encodeToString(Manifest.serializer(), wire))
        }

        private fun wireAsset(asset: ManifestAsset) = WireAsset(
            assetId = asset.asset.value,
            creationDate = TimestampText.captureText(asset.captured),
            resources = asset.resources.map(::wireResource),
        )

        /**
         * A resource as the manifest declares it: its role and type. What it is called arrives with its bytes —
         * sealed in an encrypted event — and the service reads no name or key here (decision record
         * `changes/seal-file-names`, D5).
         */
        private fun wireResource(resource: ManifestResource) = WireResource(
            role = role(resource.key.role),
            contentType = resource.contentType,
        )

        private fun role(role: ResourceRole): Role = when (role) {
            ResourceRole.PRIMARY -> Role.PRIMARY
            ResourceRole.LIVE -> Role.LIVE
        }

        private fun settled(declaration: ShareDeclaration): Boolean = when (declaration) {
            is ShareDeclaration.Settled -> true
            ShareDeclaration.Sharing -> false
        }
    }
}

/** What the backend answered a publish. */
sealed interface PublishReply {
    /** The backend took it — or refused it as older, with a snapshot at least as new already there. */
    data object Published : PublishReply

    /** The backend refused it, for [reason]. */
    data class Refused(val reason: PublishRefusal) : PublishReply

    /** No answer could be had, or one that could not be read. [fault] is how the service failed, if it did. */
    data class Unreachable(val fault: ServiceFault?) : PublishReply
}

/** Why the backend refused a publish. */
enum class PublishRefusal {
    /** The event's photos are deleted: re-sending can never change the answer. */
    EVENT_OVER,

    /** The event closed before events stopped closing, and its set is fixed: re-sending can never change it either. */
    SET_FIXED,

    /** Not taken now — not a member, a request the backend refused: the next publish tries again. */
    NOT_TAKEN,
}
