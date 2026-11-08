package app.snapsync.domain.sharing

import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.EventWindow
import app.snapsync.domain.time.Timestamp

/**
 * A member's capture range `[from, until]` — the capture dates this device contributes to its event. Both ends are
 * inclusive; it is no longer than an event may be ([EventWindow.MAX], measured in instants, as the backend measures
 * an event's), and it overlaps its event's own `[start, end]` — it MAY start before the start and end after the end,
 * as far as the member chose.
 *
 * It exists only if it obeys that rule: [of] is the one way in, and it REFUSES, it never clamps. Every entry path is
 * the join or the reconfigure, and in a shipped build none can produce a failing range — the calendar greys out what
 * breaks it, the presets obey it by construction — so a refusal is a bug or a rig caller's mistake, and quietly moving
 * it would share a range nobody chose.
 *
 * "Nothing is uploaded before the event starts" does NOT follow from this rule — a floor before the start is allowed.
 * [sharedAt] holds it instead: the range as it is shared at a moment.
 */
@ConsistentCopyVisibility
data class CaptureRange private constructor(val from: CaptureCutoff, val until: CaptureCeiling) {

    /** Whether a photo captured at [date] lies inside this range — an undated one never does. */
    fun holds(date: CaptureDate): Boolean = when (date) {
        CaptureDate.Undated -> false
        is CaptureDate.Dated -> date.at >= from.at && date.at <= until.at
    }

    /**
     * This range as it is shared at [now]: from the event's [start] while that still lies ahead — "nothing is shared
     * before the event starts" — and whole once it has passed. Still a range: it overlaps its event, so its end is not
     * before the start.
     */
    fun sharedAt(now: Timestamp, start: EventStart): CaptureRange = when {
        now >= start.at || from.at >= start.at -> this
        else -> CaptureRange(CaptureCutoff(start.at), until)
    }

    companion object {
        /** The range `[from, until]` for the event `[start, end]`, or why it cannot be one. */
        fun of(from: CaptureCutoff, until: CaptureCeiling, start: EventStart, end: EventEnd): CaptureRangeVerdict =
            when {
                until.at < from.at -> CaptureRangeVerdict.Inverted
                until.at - from.at > EventWindow.MAX -> CaptureRangeVerdict.TooLong
                else -> placed(CaptureRange(from, until), start, end)
            }

        /**
         * A well-shaped [range] placed against its event `[start, end]`: it must overlap it, both ends inclusive — a
         * range touching the event at one instant overlaps it.
         */
        private fun placed(range: CaptureRange, start: EventStart, end: EventEnd): CaptureRangeVerdict =
            if (range.until.at >= start.at && range.from.at <= end.at) {
                CaptureRangeVerdict.Ok(range)
            } else {
                CaptureRangeVerdict.OutsideEvent
            }
    }
}

/** Whether a chosen `[from, until]` is a [CaptureRange] for its event, and why not when it is not. */
sealed interface CaptureRangeVerdict {
    /** It is: [range]. */
    class Ok(val range: CaptureRange) : CaptureRangeVerdict

    /** Its end lies before its start. */
    data object Inverted : CaptureRangeVerdict

    /** It is longer than an event may be ([EventWindow.MAX]). */
    data object TooLong : CaptureRangeVerdict

    /** It does not overlap the event's own range. */
    data object OutsideEvent : CaptureRangeVerdict
}
