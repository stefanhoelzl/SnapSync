package app.snapsync.domain.upload

import app.snapsync.domain.asset.AssetId

/**
 * **What an authoritative walk writes**, decided from what it found against the rows the ledger holds:
 * - [fresh]: resources with no row yet — recorded as needing a job;
 * - [bare]: resources whose row the join-time load seeded without a capture date — their detail filled, and their
 *   photos placed in the event album first, since once dated a row is no longer bare;
 * - [departed]: the rows of photos the rows' own admission still holds but the walk did not admit — the photo left the
 *   library or the selection, or is a screenshot or below the size floors, rules a row carries no facts for. Whatever
 *   their state: the photo leaves the manifest this pass, not when its job settles (decision records
 *   `changes/selection-is-the-walk` D2, `changes/archive/2026-10-15-limited-selection-decides` D2).
 *
 * The window is the rows' own admission — a raised cutoff, a receive-only membership, the album denylist and echo
 * suppression refuse the ROW too, so their rows stay, and the photo comes back with no second upload.
 */
class WalkDiff private constructor(
    val fresh: List<FoundResource>,
    val bare: List<FoundResource>,
    val departed: List<UploadKey>,
) {
    /** The photos whose bare rows this walk dates — placed in the event album before it does. */
    val dated: Set<AssetId> get() = bare.mapTo(mutableSetOf()) { it.key.asset }

    companion object {
        /**
         * The walk's writes: [found] (the resources of the [admitted] assets it read) against [rows], the ledger's,
         * whose own admission is [window].
         */
        fun of(
            rows: List<UploadRow>,
            found: List<FoundResource>,
            admitted: Set<AssetId>,
            window: Set<AssetId>,
        ): WalkDiff {
            val keys = rows.mapTo(mutableSetOf()) { it.key }
            val bare = rows.filter { it.detail.bare }.mapTo(mutableSetOf()) { it.key }
            return WalkDiff(
                fresh = found.filter { it.key !in keys },
                bare = found.filter { it.key in bare },
                departed = rows.filter { it.key.asset in window && it.key.asset !in admitted }.map { it.key },
            )
        }

        /**
         * The admitted assets a walk need not read: every row of theirs carries its detail. A recorded resource is
         * immutable and the ledger keeps no content version, so re-reading it could only answer "already recorded";
         * an asset with a bare row is read, because only the walk can fill it.
         */
        fun known(rows: List<UploadRow>): Set<AssetId> =
            rows.groupBy { it.key.asset }.filterValues { group -> group.none { it.detail.bare } }.keys
    }
}
