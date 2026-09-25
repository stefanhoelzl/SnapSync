package app.snapsync.model

/**
 * Lifecycle of a foreign asset in the download store. Two states are terminal — [IMPORTED] and
 * [UNIMPORTABLE] — and "terminal" is what every non-terminal predicate in this store means.
 *
 * [UNIMPORTABLE] is **not** the `sync-status` no-FAILED posture being reversed. That posture governs
 * `SyncState`, which classifies the **upload** side, where `failed ≡ 0` because uploads really are retried
 * forever. It was over-read into this enum. Here a failure genuinely is tellable: the photo library takes a
 * resource's file at ingest, so a rejection of the file's CONTENT leaves no bytes to retry from, and every
 * later trigger would spend a library transaction rediscovering that (capability `receiving-photos`).
 *
 * A row in this state carries **no** `createdLocalId`: no asset was created, so it is not a suppression
 * handle, and it is prunable like any other handle-free row.
 */
enum class DownloadState {
    PENDING,
    IMPORTED,
    UNIMPORTABLE,
    ;

    /**
     * The one notion of "done with", matching the store's SQL `NOT IN ('IMPORTED', 'UNIMPORTABLE')`
     * predicates. Stated by enumeration rather than as `!= PENDING` so a future non-terminal state does
     * not silently join it.
     */
    val isTerminal: Boolean get() = this == IMPORTED || this == UNIMPORTABLE
}

/**
 * The download projection's counts, read together (capability `receiving-photos`).
 *
 * A value type rather than three reads, so the projection cannot publish a torn composite of its own counts —
 * the `sync-status` group requires each of its members to be internally consistent.
 *
 * [stillArriving] excludes `UNIMPORTABLE` rows deliberately (capability `receiving-photos`, design D8): counting
 * work that can never finish pegs the download line below completion forever, in a state the member can neither
 * act on nor dismiss. That loss reaches the operator through the crash-reporting sink instead of the screen.
 */
data class DownloadCounts(
    /** Imported foreign assets — the progress numerator. */
    val imported: Int,
    /** Foreign assets known for download that can still arrive — the progress denominator. */
    val stillArriving: Int,
    /** Foreign assets with a resource enqueued to the OS but not yet staged — the ↓-pulse signal. */
    val inFlight: Int,
)

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

/**
 * One asset a reconcile plans: its ref, its capture timestamp, and its expected resources — the unit
 * [DownloadStore.planAll] records atomically.
 */
data class PlannedAsset(val ref: AssetRef, val creationDate: String, val resources: List<PlannedResource>)

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
 * library rather than importing them again (capability `receiving-photos`).
 */
data class UnconfirmedImport(val ref: AssetRef, val createdLocalId: String)
