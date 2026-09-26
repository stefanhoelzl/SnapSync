package app.snapsync.services.downloads

import app.snapsync.model.AssetId
import app.snapsync.model.SuppressionReadiness

/**
 * The read-only suppression projection the **upload extension** consumes: the set of local
 * `createdLocalId`s of foreign assets this device has downloaded+imported. Discovery drops these so a
 * downloaded asset is never re-uploaded (the echo). Kept as its own narrow interface so the extension
 * depends on the read, not on the full app-side [DownloadService] surface. Implemented by both: [DownloadService] in the app,
 * [SuppressionService] (read-only) in the extension.
 */
interface SuppressionSource {

    /**
     * Whether [suppressedLocalIds] can answer in this process now. The upload cycle asks it once, after its
     * admission — so a process that may not create never opens the store — and before it touches anything:
     * [SuppressionReadiness.OldSchema] pauses the cycle, [SuppressionReadiness.Unavailable] skips it.
     */
    suspend fun readiness(): SuppressionReadiness

    suspend fun suppressedLocalIds(): Set<AssetId>
}
