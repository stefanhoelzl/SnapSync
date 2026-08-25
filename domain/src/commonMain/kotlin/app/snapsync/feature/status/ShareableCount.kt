package app.snapsync.feature.status

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.PermissionStatus
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.selectionRulesFor
import app.snapsync.model.EventPhotoSet
import app.snapsync.ports.CandidateSource

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
    /** The permission-aware read seam — the SAME one the status total holds, so the two cannot disagree. */
    private val source: CandidateSource,
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
    ): Int? {
        // The grant question this keeps: "can we answer at all". A DENIED or unresolved grant yields NO
        // COUNT (the surface renders no row) — which is not the same as a count of zero, and is why this
        // check does not belong in the source. Where candidates COME FROM is the source's business.
        if (!permission.grantsPhotoAccess) return null

        // ONE derivation (capability `photo-selection-policy`): the direction is resolved inside it, and a
        // non-contributing membership invokes neither reader — so the album fetch is still not paid to
        // learn that this preview counts nothing.
        val policy = SelectionPolicy(
            selectionRulesFor(
                includesUpload = includesUpload,
                cutoff = cutoff,
                ceiling = ceiling,
                suppressedAssetIds = suppressedLocalIds,
                albumExcludedAssetIds = albumExcludedAssetIds,
            ),
        )
        // Cheap AND exact: every rule decides on facts, so the count that skips the per-asset resource
        // read is the admitted-set size rather than an approximation of it (capability
        // `photo-selection-policy`). It was not always — while the animated-image rule needed a resource's
        // MIME, this facts-only path admitted a GIF on doubt while the status total excluded it, and the
        // preview over-counted by exactly the GIFs in scope.
        return EventPhotoSet(policy, source::candidates).count()
    }
}
