package app.snapsync.presentation.range

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * The length limit a range is held to: the latest end accepted for a given start — zone-aware arithmetic the
 * container's [app.snapsync.presentation.CutoffFormatter] answers.
 */
internal fun interface LatestUntil {
    fun of(from: LocalDateTime): LocalDateTime
}

/**
 * The longest a range may reach back: the earliest start for a given end — the mirror of [LatestUntil], answered by
 * the same zone-aware arithmetic.
 */
internal fun interface EarliestFrom {
    fun of(until: LocalDateTime): LocalDateTime
}

/**
 * What a range may be — the ONE difference between the surfaces that pick one: where it may lie, how long it may be,
 * and whether its end time may be blank. Everything else — the calendar, the gestures, the wheels, the rules — is
 * the same.
 */
internal class RangeBounds private constructor(
    /** The earliest start, or `null` for none. */
    val earliest: LocalDateTime?,
    /** The latest start, or `null` for none. */
    val latestStart: LocalDateTime?,
    /** The earliest end, whatever the start — the start of the range an end must reach — or `null` for none. */
    private val endFloor: LocalDateTime?,
    private val longest: LatestUntil,
    /**
     * Whether the end time starts blank and is cleared when it stops being valid (create: the host must set
     * it), rather than always set and moved to the nearest valid time (join: the event's end is the default).
     */
    val blankEndTime: Boolean,
    /** The days the calendar marks beneath the range — the event's own, where a range is chosen around one. */
    val markedDays: ClosedRange<LocalDate>?,
) {
    /** The latest end a range starting at [from] may have. */
    fun latestEnd(from: LocalDateTime): LocalDateTime = longest.of(from)

    /** The earliest end a range starting at [from] may have: after its start, and reaching [endFloor]. */
    fun earliestEnd(from: LocalDateTime): LocalDateTime = if (endFloor == null) from else maxOf(endFloor, from)

    companion object {
        /** An event being created: anywhere in time, at most [longest] long, the end time set by the host. */
        fun lastingAtMost(longest: LatestUntil): RangeBounds = RangeBounds(
            earliest = null,
            latestStart = null,
            endFloor = null,
            longest = longest,
            blankEndTime = true,
            markedDays = null,
        )

        /**
         * The range a member shares around an event's own `[start, end]`: at most [longest]
         * long and overlapping the event — so it starts no earlier than the event's start reaches back ([earliestFrom])
         * and no later than its end, and ends no earlier than its start. Its end time is always set, and the event's
         * own days are marked. The same rule the join and the reconfigure refuse a range by (`shareRangeFault`), so the
         * calendar never offers one they would refuse.
         */
        fun aroundEvent(
            start: LocalDateTime,
            end: LocalDateTime,
            longest: LatestUntil,
            earliestFrom: EarliestFrom,
        ): RangeBounds = RangeBounds(
            earliest = earliestFrom.of(start),
            latestStart = end,
            endFloor = start,
            longest = longest,
            blankEndTime = false,
            markedDays = start.date..end.date,
        )
    }
}
