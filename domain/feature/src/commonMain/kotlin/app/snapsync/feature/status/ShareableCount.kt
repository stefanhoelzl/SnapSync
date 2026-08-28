package app.snapsync.feature.status

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.selectionPolicyFor
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
 * The GRANTED path reads **cheap asset facts** — no `assetResourcesForAsset` round-trip — so sweeping the
 * range does not re-pay the ~110 ms/asset resource read. The LIMITED path never issues a fresh library
 * read (capability `limited-photo-access`): it re-filters the already-held selection snapshot in memory.
 * Where no admitted set can be stated at all — no grant, an unresolved grant, or a partial grant whose
 * snapshot has not landed — the count is **unavailable** (`null`) and the surface renders no row. Which
 * of those three it is, this source does not ask and does not need to know.
 */
class ShareableCountSource(
    /** The permission-aware read seam — the SAME one the status total holds, so the two cannot disagree. */
    private val source: CandidateSource,
    /** Downloaded/imported foreign photos, suppressed from this device's contribution (capability `photo-download`). */
    private val suppressedLocalIds: suspend () -> Set<String> = { emptySet() },
    /**
     * Denylisted-album members for the candidate cutoff — the SAME lookup the cycle gets.
     *
     * **Not memoized**, despite what this said before: the production wiring passes
     * `AppPorts.albumExcludedAssetIds` straight through to a fresh `assetsd` round-trip, so every
     * recompute pays one. That matters here because the policy must be derived BEFORE the read seam can
     * report that it has no answer, so a join surface whose grant is still unresolved now pays this
     * lookup where the old consumer-side grant check short-circuited ahead of it. Cheap, and measured
     * before it is optimized: whether a `PHAssetCollection` fetch under `NOT_DETERMINED` can surface a
     * system prompt is an open device question (decision record: this change's `design.md`).
     */
    private val albumExcludedAssetIds: suspend (CaptureCutoff) -> Set<String> = { emptySet() },
) {

    /**
     * The count for a candidate range, or `null` when no count can be produced at all.
     *
     * **No grant check lives here any more.** "Can we answer at all" is the read seam's answer, not this
     * consumer's to re-derive: a [app.snapsync.model.CandidateRead.NotReadable] read becomes `null` and
     * the surface renders no row, which is still not the same as a count of zero. Keeping a
     * `grantsPhotoAccess` gate here restated the distinction the source already owns — and it covered
     * only the grant, so it never saw the case that actually reaches a member: a partial grant whose
     * selection snapshot has not arrived (capability `limited-photo-access`).
     *
     * [includesUpload] `false` counts zero without any read — the same short-circuit `N` and the cycle
     * apply, reached through the same [SelectionPolicy.None].
     */
    suspend fun count(
        includesUpload: Boolean,
        cutoff: CaptureCutoff,
        ceiling: CaptureCeiling?,
    ): Int? {
        // ONE derivation (capability `photo-selection-policy`): the direction is resolved inside it, and a
        // non-contributing membership invokes neither reader — so the album fetch is still not paid to
        // learn that this preview counts nothing.
        val policy = selectionPolicyFor(
                includesUpload = includesUpload,
                cutoff = cutoff,
                ceiling = ceiling,
                suppressedAssetIds = suppressedLocalIds,
                albumExcludedAssetIds = albumExcludedAssetIds,
        )
        // Cheap AND exact: every rule decides on facts, so the count that skips the per-asset resource
        // read is the admitted-set size rather than an approximation of it (capability
        // `photo-selection-policy`). It was not always — while the animated-image rule needed a resource's
        // MIME, this facts-only path admitted a GIF on doubt while the status total excluded it, and the
        // preview over-counted by exactly the GIFs in scope.
        return EventPhotoSet.readable(policy, source::candidates)?.count()
    }
}
