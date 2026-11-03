package app.snapsync.services.backend

import app.snapsync.data.backend.BackendGateway
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.model.DeviceManifest
import app.snapsync.wire.api.Closed
import app.snapsync.wire.api.PublishManifestReply
import app.snapsync.wire.api.Routes

/**
 * Publish this device's per-event manifest.
 *
 * Returns `true` only when the backend confirmed the write, so the producer records the snapshot as last-uploaded
 * only on success. It enrolls nobody — a publish from a device holding no membership is
 * refused rather than creating one — and it records no upload. Synchronous and in-cycle.
 */
fun interface ManifestPublisher {
    suspend fun publish(eventId: EventId, deviceId: DeviceId, manifest: DeviceManifest): Boolean
}

/**
 * [ManifestPublisher] over the backend: `true` exactly when the backend published the snapshot — or refused it for
 * good because the event completed (or closed before events stopped closing). A publish from a device holding no membership is refused
 * ([PublishManifestReply.NotAMember]) rather than silently creating one.
 */
class BackendManifestPublisher(
    private val gateway: BackendGateway,
    /** Told when the service answers that the event's photos were deleted — none in a process with no membership. */
    private val eventOver: (suspend (EventId) -> Unit)?,
) : ManifestPublisher {

    override suspend fun publish(eventId: EventId, deviceId: DeviceId, manifest: DeviceManifest): Boolean {
        val body = manifest.onWire() ?: return false
        return when (gateway.send(Routes.PublishManifest.request(eventId.value, deviceId.value, body))) {
            PublishManifestReply.Published -> true
            // A COMPLETED event holds none: the refusal is final, so it is recorded like a publish — re-sending the
            // same snapshot every cycle could never change the answer — and it is the service's word that the photos
            // were deleted, which the membership is told.
            is Closed -> true.also { eventOver?.invoke(eventId) }
            // A legacy CLOSED event's asset sets are fixed: final too.
            is PublishManifestReply.SetFixed -> true
            else -> false
        }
    }
}
