package app.snapsync.domain.membership

import app.snapsync.domain.time.Timestamp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Whether a wake that is bounded — not a push, an opening or a join — may read the server about an event now: at most
 * once an hour per kind of read (decision record `changes/timely-background-receiving`, D5). Any doubt reads.
 */
enum class CheckCadence {
    Due,
    NotYet,
    ;

    companion object {
        /** How long a read answers for. */
        val INTERVAL: Duration = 1.hours

        /** Whether a read is due at [now], when the last was [last]. */
        fun of(last: CheckedAt, now: Timestamp): CheckCadence = when (last) {
            CheckedAt.Never, CheckedAt.Unreadable -> Due
            // A time ahead of now is a clock moved back: the recorded time says nothing.
            is CheckedAt.At -> when (last.at > now || now - last.at >= INTERVAL) {
                true -> Due
                false -> NotYet
            }
        }
    }
}

/** When a wake last read the server about an event, as recorded. */
sealed interface CheckedAt {
    data class At(val at: Timestamp) : CheckedAt

    data object Never : CheckedAt

    /** The record could not be read. */
    data object Unreadable : CheckedAt
}
