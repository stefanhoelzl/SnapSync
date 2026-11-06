package app.snapsync.ui.state

import kotlinx.serialization.Serializable

/**
 * The joined screen's counts line: per direction, how much there is and how much of it went through.
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
     * The member switched this direction off, the device has no work in it, and it brought nothing that stays. A switched-off direction that
     * nevertheless has work is a [Progress] — counted, never masked (the reason is on the arrows' derivation
     * in `StatusContainerHost`).
     */
    @Serializable
    data object Off : DirectionCount

    /**
     * The member switched this direction off, nothing in it is still moving, and [count] photos it brought stay: what
     * arrived before receiving was switched off is kept in the library, and the line says so. Never for sharing, whose
     * switching off withdraws what was not yet shared.
     */
    @Serializable
    data class Kept(val count: Int) : DirectionCount

    /** [done] of [total] went through; complete when they meet. */
    @Serializable
    data class Progress(val done: Int, val total: Int) : DirectionCount {
        val complete: Boolean get() = done >= total
    }
}
