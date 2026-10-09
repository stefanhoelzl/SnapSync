package app.snapsync.ui.components

import kotlinx.datetime.LocalDateTime

/**
 * The length limit a range is held to: the latest end the caller accepts for a given start. The caller owns
 * that arithmetic — it knows the zone and the limit; this module knows neither.
 */
fun interface LatestUntil {
    fun of(from: LocalDateTime): LocalDateTime
}

/**
 * What a range may be — the ONE difference between the surfaces that pick one —
 * create, join and the event's settings: where it may lie, how long it may be, and whether its end time may be
 * blank. Everything else — the calendar, the gestures, the wheels, the rules — is the same component.
 */
class RangeBounds private constructor(
    /** The earliest start, or `null` for none. */
    val earliest: LocalDateTime?,
    /** The latest end, or `null` for none. */
    val ceiling: LocalDateTime?,
    private val longest: LatestUntil,
    /**
     * Whether the end time starts blank and is cleared when it stops being valid (create: the host must set
     * it), rather than always set and moved to the nearest valid time (join: the event's end is the default).
     */
    val blankEndTime: Boolean,
) {
    /** The latest end a range starting at [from] may have. */
    fun latestEnd(from: LocalDateTime): LocalDateTime = longest.of(from)

    companion object {
        /** An event being created: anywhere in time, at most [longest] long, the end time set by the host. */
        fun lastingAtMost(longest: LatestUntil): RangeBounds =
            RangeBounds(earliest = null, ceiling = null, longest = longest, blankEndTime = true)

        /** A range chosen inside an event's window `[start, end]`, its end time always set. */
        fun within(start: LocalDateTime, end: LocalDateTime): RangeBounds =
            RangeBounds(earliest = start, ceiling = end, longest = { end }, blankEndTime = false)
    }
}
