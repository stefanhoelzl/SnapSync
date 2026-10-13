package app.snapsync.domain.sharing

import app.snapsync.domain.time.Timestamp
import kotlin.jvm.JvmInline

/**
 * A membership's capture-date **lower** bound: the earliest capture date this device contributes, as the member chose
 * it ([CaptureRange]) — it MAY lie before the event's start. Distinct from [app.snapsync.domain.event.EventStart] on
 * purpose: they are equal for a member who accepted the default, and confusing them is how a floor gets silently
 * lowered.
 */
@JvmInline
value class CaptureCutoff(val at: Timestamp) : Comparable<CaptureCutoff> {
    override fun compareTo(other: CaptureCutoff): Int = at.compareTo(other.at)
}

/**
 * A membership's capture-date **upper** bound (the ceiling): the latest capture date this device contributes, as the
 * member chose it ([CaptureRange]) — it MAY lie after the event's end. The bound `add-event-date-range` dropped at two
 * of four consumers; it reaches every consumer now because they all read the one admitted set.
 */
@JvmInline
value class CaptureCeiling(val at: Timestamp) : Comparable<CaptureCeiling> {
    override fun compareTo(other: CaptureCeiling): Int = at.compareTo(other.at)
}
