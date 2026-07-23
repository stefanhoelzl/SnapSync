package app.snapsync.feature.status

import app.snapsync.model.AssetFacts
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.PermissionStatus
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.EventPhotoSet
import app.snapsync.model.candidatesFromFacts
import app.snapsync.model.candidatesFromResources
import app.snapsync.model.excluding

/**
 * The join-time **shareable-count preview** (capability `join-share-count`): how many of the device's own
 * photos a **candidate** (uncommitted) capture-date range + direction would share to the event. It
 * answers "how many photos from my gallery will be shared?" on the join / switch / reconfigure decision
 * surface, recomputed as the member tunes the range.
 *
 * **One policy, not a fork** (capability `photo-selection-policy`): the count is the size of exactly the
 * admitted set the upload cycle would produce for that candidate, computed by the same
 * [SelectionPolicy.admits] every other consumer asks. Because it evaluates a **candidate** range (the one
 * the member is choosing, before commit), it constructs the policy over the candidate bounds — it
 * supplies bounds to the one admission rather than re-implementing any rule.
 *
 * It means **photos shared to this event**, not bytes transferred, so it is purely local (no backend
 * LIST) and a member whose bytes were already stored from a past event still sees every photo that will
 * appear in this event.
 *
 * The GRANTED path reads **cheap asset facts** via [factsSince] — no `assetResourcesForAsset` round-trip
 * — so sweeping the range does not re-pay the ~110 ms/asset resource read. The LIMITED path never issues
 * a fresh library read (capability `limited-photo-access`): it re-filters the already-held selection
 * snapshot in memory. Without a usable grant the count is **unavailable** (`null`) and the surface
 * renders no row.
 */
class ShareableCountSource(
    /** GRANTED-only: the cheap, cutoff-bounded facts walk (no per-asset resource read). Built in `compose/`. */
    private val factsSince: suspend (CaptureCutoff) -> List<AssetFacts>,
    /** Downloaded/imported foreign photos, suppressed from this device's contribution (capability `photo-download`). */
    private val suppressedLocalIds: suspend () -> Set<String> = { emptySet() },
    /** Denylisted-album members for the candidate cutoff — the SAME lookup the cycle gets; cached per surface upstream. */
    private val albumExcludedAssetIds: suspend (CaptureCutoff) -> Set<String> = { emptySet() },
) {

    /**
     * The count for a candidate range under the current [permission], or `null` when no count can be
     * produced (DENIED, or an unresolved NOT_DETERMINED). [selectionSnapshot] is the LIMITED selection
     * (the same resources `OwnDeviceGalleryStatusSource.refreshFrom` counts); it is ignored under GRANTED.
     *
     * [includesUpload] `false` counts zero without any read — the same short-circuit `N` and the cycle
     * apply, reached through the same [SelectionPolicy.None].
     */
    suspend fun count(
        includesUpload: Boolean,
        cutoff: CaptureCutoff,
        ceiling: CaptureCeiling?,
        permission: PermissionStatus,
        selectionSnapshot: List<Resource>?,
    ): Int? {
        val configPolicy = SelectionPolicy.from(includesUpload, cutoff, ceiling)
        if (!configPolicy.enumerates) return 0
        val policy = configPolicy.excluding(
            suppressedAssetIds = suppressedLocalIds(),
            albumExcludedAssetIds = albumExcludedAssetIds(cutoff),
        )
        // Both grants ask the SAME abstraction for the same thing — a count — differing only in which
        // backing supplies the candidates. GRANTED reads the cheap facts walk; LIMITED re-filters the
        // already-held selection snapshot and issues no library read at all. Neither pays a resource
        // read: admission is decidable on facts, and a preview never uploads.
        return when (permission) {
            PermissionStatus.GRANTED ->
                EventPhotoSet(policy) { candidatesFromFacts(factsSince(cutoff)) }.count()
            PermissionStatus.LIMITED ->
                selectionSnapshot?.let { snapshot ->
                    EventPhotoSet(policy) { candidatesFromResources(snapshot) }.count()
                }
            PermissionStatus.DENIED, PermissionStatus.NOT_DETERMINED -> null
        }
    }
}
