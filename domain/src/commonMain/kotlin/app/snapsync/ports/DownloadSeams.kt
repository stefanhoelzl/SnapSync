package app.snapsync.ports


/**
 * Submits background byte transfers for foreign resources (capability `photo-download`). On iOS this
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

/** Outcome of a per-asset import. */
sealed interface ImportResult {
    /** The asset was created; [createdLocalId] is its sanitized local identifier (the suppression handle). */
    data class Imported(val createdLocalId: String) : ImportResult

    /** The import did not complete; the asset stays importable and is retried (no terminal failure). */
    data class Failed(val message: String) : ImportResult

    /**
     * The import was still waiting on the photo library when its deadline expired, so the wait was
     * abandoned (capability `photo-download`). Distinct from [Failed] because it says something about the
     * DEVICE, not the photo: the one hang observed in the field (SNAPSYNC-6) was environmental — the same
     * asset, from the same staged bytes, imported in under a second three minutes later in a fresh
     * process. So the drain stops for this wake rather than working through the remaining assets and
     * abandoning a transaction for each.
     *
     * Like [Failed], the asset stays importable and is retried at a later wake.
     */
    data class TimedOut(val message: String) : ImportResult
}

/**
 * Imports one foreign asset's staged resources as a single new library asset (capability
 * `photo-download`). On iOS: one `PHAssetCreationRequest` adding every resource (`live`→`.pairedVideo`,
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
