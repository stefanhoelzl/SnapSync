package app.snapsync.ports

/** Lifecycle of a foreign asset in the download store. No terminal failure state — see the no-FAILED posture. */
enum class DownloadState { PENDING, IMPORTED }

/** The source identity of a foreign asset: its owning device and that device's assetId. */
data class AssetRef(val sourceDeviceId: String, val sourceAssetId: String)

/** A resource to download for an asset, as taken from the union listing. */
data class PlannedResource(
    val resourceKey: String,
    val url: String,
    val role: String,
    val contentType: String,
    val originalFilename: String,
)

/** A resource ready to import: its staged file plus the typing the importer needs. */
data class StagedResource(
    val resourceKey: String,
    val role: String,
    val contentType: String,
    val originalFilename: String,
    val stagedPath: String,
)

/** One unit of download work: a not-yet-staged resource and where to fetch it. */
data class PendingDownload(val ref: AssetRef, val resource: PlannedResource)

/** An asset ready to import: its ref and its original capture timestamp (ISO-8601, for the imported asset's date). */
data class ImportableAsset(val ref: AssetRef, val creationDate: String)

/**
 * A row whose import was never confirmed: an asset **was** created for [ref] — [createdLocalId] is its
 * identifier — but the confirmation never arrived. The import path adjudicates these against the photo
 * library rather than importing them again (capability `photo-download`).
 */
data class UnconfirmedImport(val ref: AssetRef, val createdLocalId: String)

/**
 * The read-only suppression projection the **upload extension** consumes: the set of local
 * `createdLocalId`s of foreign assets this device has downloaded+imported. Discovery drops these so a
 * downloaded asset is never re-uploaded (the echo). Kept as its own narrow interface so the extension
 * depends on the read, not on the full app-side [DownloadStore] surface.
 */
interface SuppressionSource {
    suspend fun suppressedLocalIds(): Set<String>
}

/**
 * The app-written download store (capability `download-store`). Records foreign assets selected for
 * download, their per-resource staging, and the import outcome (`createdLocalId`). Idempotency and
 * cross-event dedup are by [AssetRef]; terminal (`IMPORTED`) rows are permanent.
 */
interface DownloadStore : SuppressionSource {
    /** True if this foreign asset was already imported (skip re-download). */
    suspend fun isImported(ref: AssetRef): Boolean

    /** Record a foreign asset (with its capture [creationDate]) and its expected resources as PENDING (idempotent; never downgrades IMPORTED). */
    suspend fun plan(ref: AssetRef, creationDate: String, resources: List<PlannedResource>)

    /** The not-yet-staged resources across all non-imported assets — the download work queue. */
    suspend fun pendingDownloads(): List<PendingDownload>

    /** Mark a resource's download as sent to the OS (a background transfer now exists) — the in-flight marker. */
    suspend fun markEnqueued(ref: AssetRef, resourceKey: String)

    /** Mark a resource's bytes downloaded and durably staged at [stagedPath]. */
    suspend fun markStaged(ref: AssetRef, resourceKey: String, stagedPath: String)

    /**
     * Assets whose every expected resource is staged and that are not yet imported — ready to import.
     *
     * **Excludes rows carrying a `createdLocalId`**: those already have an asset in the library, and
     * importing them again is the duplicate this capability exists to prevent. They leave through
     * [unconfirmedImports] to be adjudicated, and re-enter here only once their marker is cleared.
     */
    suspend fun importableAssets(): List<ImportableAsset>

    /** Rows whose asset was created but whose import was never confirmed — to be adjudicated, not re-imported. */
    suspend fun unconfirmedImports(): List<UnconfirmedImport>

    /** The staged resources of an asset, to feed one PHAssetCreationRequest. */
    suspend fun stagedResources(ref: AssetRef): List<StagedResource>

    /** Mark an asset imported and record the created local identifier (the suppression handle). */
    suspend fun markImported(ref: AssetRef, createdLocalId: String)

    /**
     * Record ONLY the created local identifier, leaving the row non-terminal — the marker written from
     * **inside** the platform's change block, before the created asset is observable, so the upload echo
     * is closed even if the confirmation never arrives (capability `download-store`).
     *
     * **Not `suspend`, alone on this interface**, and not by preference: iOS's `performChanges` change
     * block cannot call a suspending function, and this write has to happen inside it or the asset is
     * observable before it is suppressed. The platform constraint is the whole reason the method exists,
     * so it shapes the signature.
     *
     * The pair *(non-terminal state, non-null `createdLocalId`)* is the **unconfirmed** row: an asset was
     * created for this ref and its import was never confirmed. Because the block always completes before
     * the library commits, a created asset always has a marker — so this is a record of an irreversible
     * act, and [pruneNonTerminal] must not delete a row that carries one.
     *
     * Idempotent; a marker written for a change that then fails is cleared by [clearCreatedLocalId].
     */
    fun recordCreatedLocalId(ref: AssetRef, createdLocalId: String)

    /**
     * Undo [recordCreatedLocalId] for a change the platform reported as **failed** — the exact mirror,
     * from the same completion callback. Never called when an import's wait is merely abandoned on its
     * deadline: that transaction may still commit, and clearing the marker is what orphans the asset it
     * creates (capability `photo-download`).
     */
    fun clearCreatedLocalId(ref: AssetRef)

    /**
     * The **success** mirror of [recordCreatedLocalId]: settle the row against the marker it already
     * holds, from the platform's completion callback itself (capability `download-store`).
     *
     * Written here rather than left to the caller because the completion is the party that LEARNS the
     * outcome, and it runs whether or not anything is still awaiting the transaction. An import whose
     * wait was abandoned on its deadline therefore settles itself, instead of staying unconfirmed until
     * some later pass pays for a synchronous, thread-blocking library lookup to discover what this
     * callback already knew.
     *
     * **Guarded on the marker.** A completion that arrives after the row's marker was cleared and
     * replaced SHALL NOT settle that row: it would mark it terminal against an identifier it no longer
     * describes, and the asset the row now points at would drop out of the suppression set. The guard
     * belongs in the store — in the `WHERE` clause, not in a caller's `if` — because two writers reach
     * this without a shared lock.
     *
     * Non-suspending, like its two siblings, because the platform's completion callback cannot call a
     * suspending function.
     */
    fun confirmCreatedLocalId(ref: AssetRef, createdLocalId: String)

    /**
     * Is [ref] still unconfirmed **with exactly [createdLocalId]** — non-terminal, and carrying that
     * marker and no other?
     *
     * The re-check a presence verdict is applied through (capability `photo-download`). Verdicts are
     * computed OUTSIDE the download controller's lock — deliberately, because the lookup blocks — and
     * applied under it, so the row can settle in between. Asking merely "is this row unconfirmed" cannot
     * tell a row still awaiting this verdict's own marker from one that has since been cleared and
     * re-imported under a different marker; applying a stale verdict to the latter overwrites a live
     * suppression handle with a dead one.
     */
    suspend fun isUnconfirmedWith(ref: AssetRef, createdLocalId: String): Boolean

    /** Count of imported foreign assets (the download-progress numerator). */
    suspend fun importedCount(): Int

    /** Count of all foreign assets known for download — pending + imported (the progress denominator). */
    suspend fun assetCount(): Int

    /** Count of foreign assets with a resource in flight — enqueued to the OS but not yet staged (the ↓-pulse signal). */
    suspend fun inFlightCount(): Int

    /** Drop non-terminal rows on leave/switch; imported rows — and any row carrying a marker — are preserved. */
    suspend fun pruneNonTerminal()

    // --- staged-byte lifetime (capability `download-store`) ------------------------------------------
    //
    // The store records WHERE an asset's bytes are; releasing them is the download side's job. These
    // reads exist so it can, and each is scoped to rows whose bytes are provably no longer needed.

    /** Staged paths of assets whose import is CONFIRMED — redundant bytes, feeding the release pass. */
    suspend fun stagedPathsOfImportedAssets(): List<String>

    /**
     * Staged paths of the rows a [pruneNonTerminal] is about to drop. Read **before** the prune: after
     * it, the paths are gone with the rows and the files are stranded with nothing referencing them.
     */
    suspend fun stagedPathsOfPrunableAssets(): List<String>

    /**
     * Drop one asset's resource rows, once its bytes have been released — so the store never records a
     * staged path for a file that no longer exists, and so a release pass over confirmed assets is
     * **self-extinguishing** (the rows that made the work findable are gone). Safe because nothing reads
     * an imported row's resources.
     */
    suspend fun dropResources(ref: AssetRef)

    /**
     * Drop the resource rows of **every** confirmed asset — the bulk half of the staged-byte reclaim.
     * Paired with [stagedPathsOfImportedAssets] this makes the reclaim self-extinguishing: the rows that
     * made the work findable are gone, so a second pass finds nothing.
     */
    suspend fun dropResourcesOfImportedAssets()
}
