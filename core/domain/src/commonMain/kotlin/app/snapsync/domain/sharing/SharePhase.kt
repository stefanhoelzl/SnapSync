package app.snapsync.domain.sharing

import app.snapsync.domain.time.Timestamp

/**
 * Where a member's own capture range stands: still [Sharing], or [Ended] — what settling waits for. The
 * member's range, never the event's: one that ends before the event settles early, one that
 * ends after it settles late. It says only WHEN settling may be tried; whether a share is settled is
 * [ShareDeclaration]'s, which also needs a look taken since the end.
 */
sealed interface SharePhase {
    /** The range has not ended: what the member shares may still grow by photos they take. */
    data object Sharing : SharePhase

    /** The range ended [at]: a look that began after then sees every photo the member took within it. */
    class Ended(val at: Timestamp) : SharePhase

    companion object {
        /** Where [range] stands at [now]. */
        fun of(range: CaptureRange, now: Timestamp): SharePhase = when (now > range.until.at) {
            true -> Ended(range.until.at)
            false -> Sharing
        }
    }
}
