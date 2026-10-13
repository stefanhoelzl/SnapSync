package app.snapsync.domain.time

import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A **point in time to the whole second** — the one instant every date role carries
 * ([app.snapsync.domain.event.EventStart], [app.snapsync.domain.sharing.CaptureCutoff], …), so a date compares as an
 * instant and never as text.
 *
 * It holds no text. How a timestamp is spelled on the wire and in the stores is a format, and formats are the
 * boundary's (`docs/patterns.md`, "Formats live at the boundary"); what IS the domain's is that a timestamp has no
 * fraction — [of] truncates, and no other way in exists.
 */
@JvmInline
value class Timestamp private constructor(val at: Instant) : Comparable<Timestamp> {
    override fun compareTo(other: Timestamp): Int = at.compareTo(other.at)

    /** The time from [other] to this one: negative when [other] is later. */
    operator fun minus(other: Timestamp): Duration = at - other.at

    companion object {
        /** [instant] as a timestamp: truncated to its second, toward the earlier instant. */
        fun of(instant: Instant): Timestamp = Timestamp(Instant.fromEpochSeconds(instant.epochSeconds))
    }
}
