package app.snapsync.feature.membership

import app.snapsync.ports.EventJoin
import app.snapsync.ports.JoinResult

/**
 * Enrolls this device into an event (capability `join-event`) by making its manifest object exist
 * under `events/<eventId>/devices/` — the physical fact of membership, so the event enumerates and can
 * notify the device immediately, before any photo upload. Returns `true` on a confirmed write.
 */
interface DeviceEnroller {
    suspend fun enroll(eventId: String, deviceId: String): JoinResult
}

/**
 * [DeviceEnroller] over the dedicated **join** request (`PUT /events/<eventId>/devices/<deviceId>`,
 * no body), which creates or reactivates the membership and writes nothing else.
 *
 * It writes **no manifest**, and that is the whole point of the split. The previous shape enrolled by
 * PUTting a register-only empty manifest, which made it a SECOND writer of a document the upload cycle
 * owns: a device rejoining an event it had already contributed to blanked its own asset set until the
 * next cycle republished it — a window in which the event union listed none of its photos. It also had
 * to reach into [app.snapsync.ports.DeviceManifestStore] to invalidate the producer's skip-if-unchanged
 * record, purely to repair the wound it had just inflicted. Neither exists now: the membership's asset
 * set survives a rejoin untouched, so an unchanged projection is correctly skipped and the union never
 * goes blank.
 *
 * A refusal is passed through rather than flattened, so the join surface can tell "this event is full"
 * from "the network failed" (capability `join-event`).
 */
class ManifestDeviceEnroller(
    private val join: EventJoin,
) : DeviceEnroller {

    override suspend fun enroll(eventId: String, deviceId: String): JoinResult =
        join.join(eventId, deviceId)
}
