package app.snapsync.ui.components

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

// The rules of the create screen's range (capability `create-event`): what the range IS, and what a settle of
// a time wheel does to it (what a day tap or drag does is `EventRangeDays.kt`). Pure, so every rule is
// unit-tested without composing anything; [AppEventRangePicker] only renders a range and feeds these the
// gestures.

/**
 * What the host has chosen for the event's window so far.
 *
 * The start is always set — preset to now. The last day ([endDay]) is preset to the start's day, but the TIME
 * on it starts blank and must be chosen, hour ([untilHour]) and minute ([untilMinute]) each: an end that
 * arrived complete would be the silent default this screen exists to prevent, so "last day shown, time blank"
 * — and "hour chosen, minute blank" — are states of their own. [endPending] is which end the next tap on a day
 * moves: while it is set, a tap on or after the start places the last day; once the last day has been placed,
 * the next tap starts a new range.
 */
data class EventRange(
    val from: LocalDateTime,
    val endDay: LocalDate = from.date,
    val untilHour: Int? = null,
    val untilMinute: Int? = null,
    val endPending: Boolean = true,
) {
    /** A range whose end time is already set — what a join or settings surface opens on. */
    constructor(from: LocalDateTime, endDay: LocalDate, untilTime: LocalTime, endPending: Boolean) :
        this(from, endDay, untilTime.hour, untilTime.minute, endPending)

    /** The end's time of day, or `null` while its hour or its minute is still blank. */
    val untilTime: LocalTime?
        get() = if (untilHour != null && untilMinute != null) LocalTime(untilHour, untilMinute) else null

    /** The chosen end, or `null` while its time is still blank. */
    val until: LocalDateTime?
        get() = untilTime?.let { LocalDateTime(endDay, it) }
}

/** Whether [time] on the chosen last day would be a valid end: after the start and within the window. */
internal fun EventRange.untilAllowed(time: LocalTime, bounds: RangeBounds): Boolean {
    val until = LocalDateTime(endDay, time)
    return until > from && until <= bounds.latestEnd(from)
}

/** Whether [time] on the start's day would be a valid start, given whatever end is already chosen. */
internal fun EventRange.fromAllowed(time: LocalTime, bounds: RangeBounds): Boolean {
    val candidate = LocalDateTime(from.date, time)
    val chosenEnd = until
    val chosenHour = untilHour
    val reachesEnd = when {
        chosenEnd != null -> chosenEnd > candidate && chosenEnd <= bounds.latestEnd(candidate)
        // An end hour chosen without its minute must keep a minute that would still be a valid end.
        chosenHour != null -> hourHasAllowedMinute(chosenHour) { end ->
            LocalDateTime(endDay, end).let { it > candidate && it <= bounds.latestEnd(candidate) }
        }
        else -> endDay <= bounds.latestEnd(candidate).date
    }
    return reachesEnd && bounds.earliest.let { it == null || candidate >= it }
}

/** Whether the range is complete and inside [bounds] — what a surface that confirms a range asks. */
internal fun EventRange.isValid(bounds: RangeBounds): Boolean {
    val end = until
    return end != null && untilAllowed(end.time, bounds) && fromAllowed(from.time, bounds)
}

/**
 * The Until hour wheel settled on [hour]. It never fills the minute: from a blank minute only the hour is set,
 * to the nearest hour that has any valid minute. A minute already chosen is kept where it stays valid and
 * otherwise moves to the nearest valid time, so a bad range is unreachable rather than refused.
 */
internal fun EventRange.settleUntilHour(hour: Int, bounds: RangeBounds): EventRange {
    val minute = untilMinute ?: return copy(untilHour = null).fillUntilHour(hour, bounds)
    return settleUntil(hour, minute, bounds)
}

/**
 * The Until minute wheel started moving while the hour is blank: the hour fills with [currentHour] — the
 * clock's — or the nearest hour that has a valid minute. An hour already chosen is kept.
 */
internal fun EventRange.fillUntilHour(currentHour: Int, bounds: RangeBounds): EventRange =
    if (untilHour != null) {
        this
    } else {
        nearestHour(currentHour) { untilAllowed(it, bounds) }?.let { copy(untilHour = it) } ?: this
    }

/**
 * The Until minute wheel settled on [minute]. With the hour still blank (a tap rather than a drag) it fills as
 * [fillUntilHour] does, from [currentHour].
 */
internal fun EventRange.settleUntilMinute(minute: Int, bounds: RangeBounds, currentHour: Int): EventRange {
    val hour = fillUntilHour(currentHour, bounds).untilHour ?: return this
    return settleUntil(hour, minute, bounds)
}

private fun EventRange.settleUntil(hour: Int, minute: Int, bounds: RangeBounds): EventRange =
    nearestTime(hour, minute) { untilAllowed(it, bounds) }
        ?.let { copy(untilHour = it.hour, untilMinute = it.minute) } ?: this

/** The From hour wheel settled on [hour]; the minutes are kept where they stay valid. */
internal fun EventRange.settleFromHour(hour: Int, bounds: RangeBounds): EventRange =
    settleFrom(hour, from.minute, bounds)

/** The From minute wheel settled on [minute]. */
internal fun EventRange.settleFromMinute(minute: Int, bounds: RangeBounds): EventRange =
    settleFrom(from.hour, minute, bounds)

private fun EventRange.settleFrom(hour: Int, minute: Int, bounds: RangeBounds): EventRange =
    nearestTime(hour, minute) { fromAllowed(it, bounds) }?.let { copy(from = LocalDateTime(from.date, it)) } ?: this
