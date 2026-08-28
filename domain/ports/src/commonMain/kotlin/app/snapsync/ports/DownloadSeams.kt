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

    /**
     * The import did not complete.
     *
     * This is an OBSERVED outcome — the library reported the change failed — and it is the only kind of
     * failure this seam reports. There is deliberately no "we stopped waiting" case: nothing bounds an
     * import in time any more (capability `photo-download`), because a wall-clock bound expires against
     * transactions that are alive, and the wake it would otherwise protect is bounded by `OsReceipt`
     * instead. An import that never reports never returns, and stays claimed for the life of the process.
     *
     * [consumedResources] is what separates a failure worth retrying from one that never can be, and it is
     * a **platform fact the adapter observes**, not an interpretation the caller may make. The photo library
     * takes a resource's file when it INGESTS it — before validating the content and before the commit — so
     * a rejection of the file's content leaves nothing on disk to retry from, while a rejection of the
     * request's shape leaves every file untouched. Measured 2026-08-26 (iOS 26.2): `InvalidResource`
     * consumed the file with no asset created; `ChangeNotSupported` consumed nothing.
     *
     * `true` therefore means the row must SETTLE — a staged resource is never re-downloaded, so retrying it
     * imports from files that no longer exist, forever. `false` means the bytes are intact and a later
     * trigger should try again. An adapter that cannot tell SHALL answer `false`: retrying costs a
     * transaction, settling wrongly costs the photo.
     */
    data class Failed(val message: String, val consumedResources: Boolean = false) : ImportResult
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
