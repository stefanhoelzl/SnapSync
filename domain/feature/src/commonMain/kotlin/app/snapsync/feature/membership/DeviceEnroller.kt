package app.snapsync.feature.membership

import app.snapsync.model.DeviceManifest
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.Enrollment
import app.snapsync.model.encodeToJson

/**
 * Enrolls this device into an event (capability `join-event`) by making its manifest object exist
 * under `events/<eventId>/devices/` — the physical fact of membership, so the event enumerates and can
 * notify the device immediately, before any photo upload. Returns `true` on a confirmed write.
 */
interface DeviceEnroller {
    suspend fun enroll(eventId: String, deviceId: String): Boolean
}

/**
 * [DeviceEnroller] that writes a **register-only empty** device manifest (`{ deviceId, assets: [] }`)
 * via the existing [Enrollment] seam (`PUT /events/<eventId>/devices/<deviceId>`). The empty
 * manifest contributes nothing to the complete-only union read; it exists only to register membership.
 * The device's real asset manifest is written later by the normal upload cycle (last-write-wins).
 *
 * **It is the second writer of that resource, so it invalidates the producer's skip-if-unchanged
 * record.** [DeviceManifestProducer] skips its PUT when the projection equals what it last successfully
 * wrote — a belief about the *server*, which this empty PUT has just made false. Without the clear, a
 * device re-joining an event it has already contributed to (after a leave, a durable state reset, or a
 * reinstall) computes the same projection as before, matches the stale record, skips — and the event
 * union is left holding `assets: []` for this device, hiding every photo it uploaded, permanently and
 * with no error anywhere. The event id in the record already covers a *switch*; it cannot cover the
 * same event twice.
 *
 * The clear is deliberately here rather than in [JoinEvent] or the state reset: this is where the belief
 * is falsified, so every enrolling path is covered without any caller remembering. (A reset, by
 * contrast, changes nothing on the server — the record is still true at that moment.)
 */
class ManifestDeviceEnroller(
    private val uploader: Enrollment,
    private val manifestStore: DeviceManifestStore,
) : DeviceEnroller {

    override suspend fun enroll(eventId: String, deviceId: String): Boolean {
        val emptyManifest = DeviceManifest(deviceId = deviceId, assets = emptyList())
        if (!uploader.put(eventId, deviceId, emptyManifest.encodeToJson())) return false
        // Only on success — an unconfirmed PUT did not change the server, so the record is still true and
        // clearing it would buy a redundant PUT next cycle. Same rule the producer records under.
        manifestStore.clearLastUploaded()
        return true
    }
}
