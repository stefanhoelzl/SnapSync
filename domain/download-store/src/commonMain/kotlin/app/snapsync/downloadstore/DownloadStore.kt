package app.snapsync.downloadstore

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

    /** Assets whose every expected resource is staged and that are not yet imported — ready to import (with capture date). */
    suspend fun importableAssets(): List<ImportableAsset>

    /** The staged resources of an asset, to feed one PHAssetCreationRequest. */
    suspend fun stagedResources(ref: AssetRef): List<StagedResource>

    /** Mark an asset imported and record the created local identifier (the suppression handle). */
    suspend fun markImported(ref: AssetRef, createdLocalId: String)

    /** Count of imported foreign assets (the download-progress numerator). */
    suspend fun importedCount(): Int

    /** Count of all foreign assets known for download — pending + imported (the progress denominator). */
    suspend fun assetCount(): Int

    /** Count of foreign assets with a resource in flight — enqueued to the OS but not yet staged (the ↓-pulse signal). */
    suspend fun inFlightCount(): Int

    /** Drop non-terminal rows on leave/switch; imported rows are preserved. */
    suspend fun pruneNonTerminal()
}
