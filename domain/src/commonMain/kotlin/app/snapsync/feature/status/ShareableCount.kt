package app.snapsync.feature.status

import app.snapsync.model.Contribution
import app.snapsync.model.PermissionStatus
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.RawAsset
import app.snapsync.model.Resource
import app.snapsync.model.excludedAssetIds
import app.snapsync.model.originExcludedAssetIds

/**
 * The join-time **shareable-count preview** (capability `join-share-count`): how many of the device's own
 * photos a **candidate** (uncommitted) cutoff + direction would share to the event. It answers "how many
 * photos from my gallery will be shared?" on the join / switch / reconfigure decision surface, recomputed
 * as the member tunes the cutoff.
 *
 * **One policy, not a fork** (capability `photo-selection-policy`): the count is the size of exactly the set
 * the upload cycle would admit for that candidate — direction includes upload, `creationDate >= cutoff`, and
 * no origin exclusion — computed by the same [originExcludedAssetIds] / [excludedAssetIds] rule the cycle and
 * the status total `N` use. It means **photos shared to this event**, not bytes transferred, so it is purely
 * local (no backend LIST) and a member whose bytes were already stored from a past event still sees every
 * photo that will appear in this event.
 *
 * The GRANTED path reads **cheap `PHAsset` facts** ([RawAsset]) via [factsSince] — no `assetResourcesForAsset`
 * round-trip — so sweeping the cutoff does not re-pay the ~110 ms/asset resource read. The LIMITED path never
 * issues a fresh library read (capability `limited-photo-access` rule ①): it re-filters the already-held
 * selection snapshot in memory. Without a usable grant the count is **unavailable** (`null`) and the surface
 * renders no row.
 */
class ShareableCountSource(
    /** GRANTED-only: the cheap, cutoff-bounded facts walk (no per-asset resource read). Built in `compose/`. */
    private val factsSince: suspend (String) -> List<RawAsset>,
    /** Downloaded/imported foreign photos, suppressed from this device's contribution (capability `photo-download`). */
    private val suppressedLocalIds: suspend () -> Set<String> = { emptySet() },
    /** Denylisted-album members for the candidate cutoff — the SAME lookup the cycle gets; cached per surface upstream. */
    private val albumExcludedAssetIds: suspend (String) -> Set<String> = { emptySet() },
) {

    /**
     * The count for a candidate [contribution] under the current [permission], or `null` when no count can
     * be produced (DENIED, or an unresolved NOT_DETERMINED). [selectionSnapshot] is the LIMITED selection
     * (the same resources `OwnDeviceGalleryStatusSource.refreshFrom` counts); it is ignored under GRANTED.
     */
    suspend fun count(
        contribution: Contribution,
        permission: PermissionStatus,
        selectionSnapshot: List<Resource>?,
    ): Int? {
        val (cutoff, until) = when (contribution) {
            // A non-contributing candidate (Share off / DownloadOnly) counts zero without any read.
            Contribution.None -> return 0
            is Contribution.Since -> contribution.cutoff to contribution.until
        }
        val suppressed = suppressedLocalIds()
        val albumExcluded = albumExcludedAssetIds(cutoff)
        return when (permission) {
            PermissionStatus.GRANTED ->
                shareableCountFromAssets(factsSince(cutoff), cutoff, until, suppressed, albumExcluded)
            PermissionStatus.LIMITED ->
                selectionSnapshot?.let { shareableCountFromSnapshot(it, cutoff, until, suppressed, albumExcluded) }
            PermissionStatus.DENIED, PermissionStatus.NOT_DETERMINED -> null
        }
    }
}

/**
 * The count of distinct own assets admitted at [cutoff] from cheap [assets] facts — pre-cutoff, suppressed
 * (echo), and origin-excluded assets removed, mirroring `OwnDeviceGalleryStatusSource.compute`.
 */
fun shareableCountFromAssets(
    assets: List<RawAsset>,
    cutoff: String,
    until: String?,
    suppressed: Set<String>,
    albumExcluded: Set<String>,
): Int {
    val originExcluded = originExcludedAssetIds(assets) + albumExcluded
    return assets.asSequence()
        .filter { it.creationDate >= cutoff && (until == null || it.creationDate <= until) }
        .map { it.assetId }
        .filter { it !in suppressed }
        .filter { it !in originExcluded }
        .distinct()
        .count()
}

/**
 * The count over a **resource** snapshot (the LIMITED selection): the same three-way subtraction as the
 * [RawAsset] overload, over resources carrying the origin facts in their metadata.
 */
fun shareableCountFromSnapshot(
    resources: List<Resource>,
    cutoff: String,
    until: String?,
    suppressed: Set<String>,
    albumExcluded: Set<String>,
): Int {
    val originExcluded = excludedAssetIds(resources) + albumExcluded
    return resources.asSequence()
        .filter {
            val cd = it.metadata[RESOURCE_META_CREATION_DATE] ?: ""
            cd >= cutoff && (until == null || cd <= until)
        }
        .map { it.assetId }
        .filter { it !in suppressed }
        .filter { it !in originExcluded }
        .distinct()
        .count()
}
