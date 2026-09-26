package app.snapsync.ports

import app.snapsync.model.PendingDownload

/**
 * Submits background byte transfers for foreign resources (capability `receiving-photos`). On iOS this
 * is a background `URLSession` (discretionary/Wi-Fi); completions are delivered out of band to
 * [DownloadController.onResourceStaged] after the impl moves each finished file to durable App-Group
 * staging. A failed transfer leaves the resource pending (no terminal failure) for a later retry.
 */
interface PhotoDownloadJobs {
    /** Enqueue downloads for the given not-yet-staged resources (idempotent; already-running keys are skipped). */
    suspend fun enqueue(downloads: List<PendingDownload>)

    /** Cancel all in-flight transfers (leave/switch). */
    suspend fun cancelAll()
}
