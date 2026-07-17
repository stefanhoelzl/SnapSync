package app.snapsync.feature.membership

import app.snapsync.model.DeviceManifest
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
 */
class ManifestDeviceEnroller(
    private val uploader: Enrollment,
) : DeviceEnroller {

    override suspend fun enroll(eventId: String, deviceId: String): Boolean {
        val emptyManifest = DeviceManifest(deviceId = deviceId, assets = emptyList())
        return uploader.put(eventId, deviceId, emptyManifest.encodeToJson())
    }
}
