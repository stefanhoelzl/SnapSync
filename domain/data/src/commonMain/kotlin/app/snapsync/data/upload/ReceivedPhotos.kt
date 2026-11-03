package app.snapsync.data.upload

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.EchoReadiness

/**
 * **The photos this device received**, as the upload asks of them — MIGRATING: the download store is still an old-zone
 * service, which the composition implements this over until phase 4.5 moves the store into this layer
 * (`PENDING_CLEANUPS.md`, "The selection as the old zones reach it"). A received photo is never shared back: its
 * ids are the echo suppression a contribution applies, and an upload pass runs only once the store can answer.
 */
interface ReceivedPhotos {
    /** Whether the store can answer now — the extension opens it read-only, and pauses until the app migrated it. */
    suspend fun readiness(): EchoReadiness

    /** Whether [event]'s received photos have been recognised in this device's library. */
    suspend fun recognised(event: EventId): Boolean

    /** The ids of the photos received into this device's library. */
    suspend fun suppressed(): Set<AssetId>
}
