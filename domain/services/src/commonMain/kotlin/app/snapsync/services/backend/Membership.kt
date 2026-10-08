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
 * Tells the shared event that **this device is leaving it** (capability `manage-membership`), and whether it holds
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
 * difference: a `409` is the event at capacity — a sentence the join surface can show — while a `404` means the
 * event is gone and anything else is a failure a retry may heal.
 */
class BackendEventJoin(private val backend: AuthenticatedBackend) : EventJoin {

    override suspend fun join(eventId: String, deviceId: String): JoinResult =
        when (val reply = backend.joinEvent(eventId, deviceId)) {
            is Reply.Ok -> JoinResult.JOINED
            is Reply.Refused -> when (reply.status) {
                HttpStatus.CONFLICT -> JoinResult.EVENT_FULL
                // A closed (or completed) event admits nobody (capability `event-lifetime`).
                HttpStatus.GONE -> JoinResult.EVENT_CLOSED
                HttpStatus.NOT_FOUND -> JoinResult.EVENT_NOT_FOUND
                // Still refused after the recovery: no credential the service accepts (the attestation says why).
                HttpStatus.UNAUTHORIZED -> JoinResult.UNVERIFIED
                else -> JoinResult.FAILED
            }
            is Reply.Unreachable -> JoinResult.FAILED
        }
}

/**
 * [ManifestPublisher] over the backend: `true` exactly when the backend served the write — or refused it for good
 * because the event has closed. A publish from a device holding no membership is refused (`409`) rather than
 * silently creating one.
 */
class BackendManifestPublisher(private val backend: AuthenticatedBackend) : ManifestPublisher {

    override suspend fun publish(eventId: String, deviceId: String, manifest: DeviceManifest): Boolean =
        when (val reply = backend.publishManifest(eventId, deviceId, manifest)) {
            is Reply.Ok -> true
            // A CLOSED event's asset sets are fixed and a COMPLETED one holds none (capability `photo-sharing`): the
            // refusal is final, so it is recorded like a publish — re-sending the same snapshot every cycle could
            // never change the answer.
            is Reply.Refused ->
                reply.status == HttpStatus.GONE ||
                    (reply.status == HttpStatus.CONFLICT && CLOSED_MARK in reply.body)
            is Reply.Unreachable -> false
        }

    private companion object {
        const val CLOSED_MARK = "\"closed\""
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
    private val backend: AuthenticatedBackend,
    private val identity: PersistedDeviceIdentity,
) : LeaveNotifier {

    override suspend fun notifyLeaving(eventId: String, received: Boolean): Result<Unit> {
        val id = runCatchingCancellable { identity.deviceId() }.getOrElse { return Result.failure(it) }
        val reply = backend.leaveEvent(eventId, id, received)
        // An event the backend no longer holds has nothing left to leave: the leave is as done as it will ever be.
        if (reply is Reply.Refused && reply.status == HttpStatus.NOT_FOUND) return Result.success(Unit)
        return reply.toResult("leave $eventId/$id")
    }
}
