package app.snapsync.domain.event

import app.snapsync.domain.time.Timestamp

/**
 * An event's dates `[start, end]`, both inclusive: it starts no later than it ends. The end bounds which photos may be
 * shared and closes nothing. [of] is the one way in. How long an event may be ([EventWindow.MAX]) is a rule of its
 * creation, not of an event that exists: one the server holds or this device stored is taken as it is.
 */
@ConsistentCopyVisibility
data class EventRange private constructor(val start: EventStart, val end: EventEnd) {

    /** Where [now] falls against the event's dates. */
    fun phaseAt(now: Timestamp): EventPhase = when {
        now < start.at -> EventPhase.Upcoming
        now > end.at -> EventPhase.Ended
        else -> EventPhase.Running
    }

    companion object {
        /** The event `[start, end]`, or why it cannot be one. */
        fun of(start: EventStart, end: EventEnd): EventRangeVerdict = when (end.at < start.at) {
            true -> EventRangeVerdict.Inverted
            false -> EventRangeVerdict.Ok(EventRange(start, end))
        }
    }
}

/** Whether `[start, end]` is an [EventRange], and why not when it is not. */
sealed interface EventRangeVerdict {
    class Ok(val range: EventRange) : EventRangeVerdict

    /** Its end lies before its start. */
    data object Inverted : EventRangeVerdict
}

/** Where a moment falls against an event's dates. There is no "closed": nothing closes. */
enum class EventPhase {
    Upcoming,
    Running,

    /** Past its end: no photo taken now is the event's, and every member can be done with it. */
    Ended,
}
