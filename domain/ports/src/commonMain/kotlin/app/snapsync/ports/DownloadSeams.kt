package app.snapsync.ports

import app.snapsync.model.AssetRef
import app.snapsync.model.ImportResult
import app.snapsync.model.PendingDownload
import app.snapsync.model.StagedResource

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

/**
 * Imports one foreign asset's staged resources as a single new library asset (capability
 * `receiving-photos`). On iOS: one `PHAssetCreationRequest` adding every resource (`live`→`.pairedVideo`,
 * `primary`→`.photo`/`.video`/`.audio` by `contentType`) into the camera roll. The impl MUST record
 * the created local id into the download store **inside** the `performChanges` change block (before the
 * asset is observable) to close the upload echo; it returns that same id so the controller can mark the
 * asset imported.
 */
interface PhotoLibraryImporter {
    /**
     * Import the [resources] as one asset whose capture timestamp is [creationDate] (ISO-8601), so the
     * imported photo sorts by its **original** date in the library rather than the import time.
     */
    suspend fun import(ref: AssetRef, resources: List<StagedResource>, creationDate: String): ImportResult
}
