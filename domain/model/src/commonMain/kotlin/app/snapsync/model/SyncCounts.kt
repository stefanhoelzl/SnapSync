package app.snapsync.model

import kotlinx.serialization.Serializable

/**
 * The joined screen's counts line (capability `sync-status`, "The joined screen counts what was shared and
 * received"): per direction, how much there is and how much of it went through.
 *
 * Built from the SAME two pairs of numbers the direction arrows are derived from — `synced`/`total` for the
 * upload side, `downloaded`/`total` for the download side — so the counts and the arrows (and "Up to date") can
 * never disagree. The reduction sets it only while the health is "Up to date" or syncing; in every other status
 * the numbers are unknown or zero and it is `null`.
 */
@Serializable
data class SyncCounts(val shared: DirectionCount, val received: DirectionCount)

/** One direction of [SyncCounts]. */
@Serializable
sealed interface DirectionCount {
    /**
     * The member switched this direction off AND the device has no work in it. A switched-off direction that
     * nevertheless has work is a [Progress] — counted, never masked (the reason is on the arrows' derivation
     * in `StatusContainerHost`).
     */
    @Serializable
    data object Off : DirectionCount

    /** [done] of [total] went through; complete when they meet. */
    @Serializable
    data class Progress(val done: Int, val total: Int) : DirectionCount {
        val complete: Boolean get() = done >= total
    }
}
