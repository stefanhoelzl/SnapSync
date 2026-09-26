package app.snapsync.services.backend

import app.snapsync.model.DeviceManifest
import app.snapsync.model.JoinResult
import app.snapsync.model.Reply
import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.toResult
import app.snapsync.services.identity.PersistedDeviceIdentity

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
 * only on success (capability `photo-sharing`). It enrolls nobody — a publish from a device holding no membership is
 * refused rather than creating one — and it records no upload. Synchronous and in-cycle.
 */
fun interface ManifestPublisher {
    suspend fun publish(eventId: String, deviceId: String, manifest: DeviceManifest): Boolean
}

/**
 * Tells the shared event that **this device is leaving it** (capability `manage-membership`). The event then renames
 * this device's manifest to its departed `.left.json` sibling and, once the last active member has gone, reaps the
 * event and garbage-collects its now-unreferenced bytes.
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
    suspend fun notifyLeaving(eventId: String): Result<Unit>
}

/**
 * [EventJoin] over the backend. The refusals are mapped rather than flattened, because the caller can act on the
 * difference: a `409` is the event at capacity — a sentence the join surface can show — while a `404` means the
 * event is gone and anything else is a failure a retry may heal.
 */
class BackendEventJoin(private val backend: AuthenticatedBackend) : EventJoin {

    override suspend fun join(eventId: String, deviceId: String): JoinResult =
        when (val reply = backend.joinEvent(eventId, deviceId)) {
            is Reply.Ok -> JoinResult.JOINED
            is Reply.Refused -> when (reply.status) {
                CONFLICT -> JoinResult.EVENT_FULL
                NOT_FOUND -> JoinResult.EVENT_NOT_FOUND
                else -> JoinResult.FAILED
            }
            is Reply.Malformed, is Reply.Unreachable -> JoinResult.FAILED
        }

    private companion object {
        const val CONFLICT = 409
        const val NOT_FOUND = 404
    }
}

/**
 * [ManifestPublisher] over the backend: `true` exactly when the backend served the write. A publish from a device
 * holding no membership is refused (`409`) rather than silently creating one.
 */
class BackendManifestPublisher(private val backend: AuthenticatedBackend) : ManifestPublisher {

    override suspend fun publish(eventId: String, deviceId: String, manifest: DeviceManifest): Boolean =
        backend.publishManifest(eventId, deviceId, manifest) is Reply.Ok
}

/**
 * [LeaveNotifier] over the backend, for the device [identity] names.
 *
 * [identity] is read per call, at the moment the request is built, never at construction: on iOS resolving it reads
 * the Keychain, which is unavailable before first unlock — binding it eagerly would drag that read into composition
 * and abort a locked background launch. An unreadable identity is a failed [Result] like any other.
 */
class BackendLeaveNotifier(
    private val backend: AuthenticatedBackend,
    private val identity: PersistedDeviceIdentity,
) : LeaveNotifier {

    override suspend fun notifyLeaving(eventId: String): Result<Unit> {
        val id = runCatchingCancellable { identity.deviceId() }.getOrElse { return Result.failure(it) }
        return backend.leaveEvent(eventId, id).toResult("leave $eventId/$id")
    }
}
