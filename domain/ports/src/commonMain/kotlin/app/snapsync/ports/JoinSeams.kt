package app.snapsync.ports

import app.snapsync.model.JoinResult

/**
 * The **join** seam: create or reactivate this device's membership in an event.
 *
 * Carries **no body**. Joining and contributing are separate requests on the device API: this one owns
 * membership and is the only request that decides capacity, while [ManifestPublisher] owns what the
 * device shares. The split is what removes the register-only empty manifest — a second writer of the
 * manifest whose only job was to make membership exist, and which blanked a rejoining device's
 * contribution until the next cycle republished it.
 *
 * iOS backs this with the Darwin HTTP client; tests fake it.
 */
interface EventJoin {
    suspend fun join(eventId: String, deviceId: String): JoinResult
}

/**
 * The **contribution** seam: publish this device's per-event manifest.
 *
 * Returns `true` only when the backend confirmed the write, so the producer records the snapshot as
 * last-uploaded only on success (capability `photo-sharing`). It enrolls nobody — a publish from a
 * device holding no membership is refused rather than creating one — and it records no upload.
 *
 * Synchronous and in-cycle: no background `URLSession`, no app involvement.
 */
interface ManifestPublisher {
    suspend fun publish(eventId: String, deviceId: String, json: String): Boolean
}
