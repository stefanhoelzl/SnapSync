package app.snapsync.services.backend

import app.snapsync.model.DeviceManifest
import app.snapsync.model.JoinResult
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.wire.api.Closed
import app.snapsync.wire.api.EventNotFound
import app.snapsync.wire.api.JoinEventReply
import app.snapsync.wire.api.LeaveEventReply
import app.snapsync.wire.api.PublishManifestReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.Unauthorized

/**
 * Create or reactivate this device's membership in an event.
 *
 * Carries **no body**. Joining and contributing are separate requests on the device API: this one owns membership
 * and is the only request that decides capacity, while [ManifestPublisher] owns what the device shares. The split is
 * what removes the register-only empty manifest — a second writer of the manifest whose only job was to make
 * membership exist, and which blanked a rejoining device's contribution until the next cycle republished it.
 */
fun interface EventJoin {
    suspend fun join(eventId: String, deviceId: String): JoinResult
}

/**
 * Publish this device's per-event manifest.
 *
 * Returns `true` only when the backend confirmed the write, so the producer records the snapshot as last-uploaded
 * only on success. It enrolls nobody — a publish from a device holding no membership is
 * refused rather than creating one — and it records no upload. Synchronous and in-cycle.
 */
fun interface ManifestPublisher {
    suspend fun publish(eventId: String, deviceId: String, manifest: DeviceManifest): Boolean
}

/**
 * Tells the shared event that **this device is leaving it**, and whether it holds
 * every photo of the others ([received]). The backend records the membership as gone — `done` when it left having
 * everything, `left` otherwise — keeps what it shared in the union, and closes an ended event the leave leaves with
 * nobody still unsettled.
 *
 * **This device, always — which is why no `deviceId` crosses it.** The identity doing the leaving is a per-process
 * constant, never a choice the caller makes; taking it as a parameter would widen the service to "make any device
 * leave any event" — a capability nothing needs, and one an id mix-up could exercise by accident.
 *
 * **Best-effort by contract.** Implementations return a failed [Result] and never throw, so the caller's local
 * teardown proceeds regardless: a dropped notify leaves the backend membership in place — the accepted
 * abandon-leak — and never blocks or rolls back leaving locally.
 */
fun interface LeaveNotifier {
    /** Announce that this device has left [eventId]. Never throws; failure arrives as a failed [Result]. */
    suspend fun notifyLeaving(eventId: String, received: Boolean): Result<Unit>
}

/**
 * [EventJoin] over the backend. The refusals are mapped rather than flattened, because the caller can act on the
 * difference: [JoinEventReply.Full] is the event at capacity — a sentence the join surface can show — while
 * [EventNotFound] means the event is gone and anything else is a failure a retry may heal.
 */
class BackendEventJoin(private val gateway: BackendGateway) : EventJoin {

    override suspend fun join(eventId: String, deviceId: String): JoinResult =
        when (gateway.send(Routes.JoinEvent.request(eventId, deviceId), obtainFirst = true)) {
            JoinEventReply.Joined -> JoinResult.JOINED
            JoinEventReply.Full -> JoinResult.EVENT_FULL
            // A closed (or completed) event admits nobody.
            is Closed -> JoinResult.EVENT_CLOSED
            EventNotFound -> JoinResult.EVENT_NOT_FOUND
            // Still refused after the recovery: no credential the service accepts (the attestation says why).
            Unauthorized -> JoinResult.UNVERIFIED
            else -> JoinResult.FAILED
        }
}

/**
 * [ManifestPublisher] over the backend: `true` exactly when the backend published the snapshot — or refused it for
 * good because the event has closed. A publish from a device holding no membership is refused
 * ([PublishManifestReply.NotAMember]) rather than silently creating one.
 */
class BackendManifestPublisher(private val gateway: BackendGateway) : ManifestPublisher {

    override suspend fun publish(eventId: String, deviceId: String, manifest: DeviceManifest): Boolean {
        val body = manifest.onWire() ?: return false
        return when (gateway.send(Routes.PublishManifest.request(eventId, deviceId, body))) {
            PublishManifestReply.Published -> true
            // A CLOSED event's asset sets are fixed and a COMPLETED one holds none: the
            // refusal is final, so it is recorded like a publish — re-sending the same snapshot every cycle could
            // never change the answer.
            is Closed, is PublishManifestReply.SetFixed -> true
            else -> false
        }
    }
}

/**
 * [LeaveNotifier] over the backend, for the device [identity] names.
 *
 * [identity] is read per call, at the moment the request is built, never at construction: on iOS resolving it reads
 * the Keychain, which is unavailable before first unlock — binding it eagerly would drag that read into composition
 * and abort a locked background launch. An unreadable identity is a failed [Result] like any other.
 */
class BackendLeaveNotifier(
    private val gateway: BackendGateway,
    private val identity: PersistedDeviceIdentity,
) : LeaveNotifier {

    override suspend fun notifyLeaving(eventId: String, received: Boolean): Result<Unit> {
        val id = runCatchingCancellable { identity.deviceId() }.getOrElse { return Result.failure(it) }
        return when (val reply = gateway.send(Routes.LeaveEvent.request(eventId, id, received))) {
            LeaveEventReply.Left -> Result.success(Unit)
            // An event the backend no longer holds has nothing left to leave: the leave is as done as it will ever be.
            EventNotFound -> Result.success(Unit)
            else -> Result.failure(IllegalStateException("leave $eventId/$id: $reply"))
        }
    }
}
