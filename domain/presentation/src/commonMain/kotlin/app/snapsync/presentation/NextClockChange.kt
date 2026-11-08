package app.snapsync.presentation

import app.snapsync.domain.event.Event
import app.snapsync.domain.time.Timestamp
import kotlin.time.Duration.Companion.minutes

object NextClockChange {
    /**
     * When the joined screen's clock-driven lines can next change for [event], from [now].
     *
     * While the event's start or end is still ahead, every minute: the dates line counts down to them ("starts in …",
     * "ends in …") at a minute's grain, and the not-started line retires at the start. Once both have passed, nothing
     * moves by the minute any more — only the "guests can join until" line, which goes at the event's open-until date,
     * and
     * the device's today, which changes at local midnight: the first of the two.
     */
    internal fun of(event: Event, now: Timestamp, clock: CutoffFormatter): Timestamp =
        // At the end itself the event still runs: it has ended only once its end has passed, a minute on.
        if (now < event.range.start.at || now <= event.range.end.at) {
            Timestamp.of(now.at + 1.minutes)
        } else {
            firstOf(event.openUntil?.at?.takeIf { it > now }, clock.nextMidnight(now))
        }

    private fun firstOf(openUntil: Timestamp?, midnight: Timestamp): Timestamp =
        if (openUntil != null && openUntil < midnight) openUntil else midnight
}
