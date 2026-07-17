package app.snapsync.ports

/**
 * The synchronous, in-cycle upload seam for the device manifest. Returns `true` only when the edge
 * confirmed the store (`PUT /event/<eventId>/device/<deviceId>`), so the producer records the snapshot
 * as last-uploaded only on success. iOS backs this with the Darwin HTTP client; tests fake it.
 */
interface DeviceManifestUploader {
    suspend fun put(eventId: String, deviceId: String, json: String): Boolean
}
