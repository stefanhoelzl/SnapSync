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
 * The start is always set — preset to the moment the screen opened. The last day ([endDay]) is preset to the
 * start's day, but the TIME on it ([untilTime]) starts blank and must be chosen: an end that arrived complete
 * would be the silent default this screen exists to prevent, so "last day shown, time blank" is a state of
 * its own. [endPending] is which end the next tap on a day moves: while it is set, a tap on or after the
 * start places the last day; once the last day has been placed, the next tap starts a new range.
 */
data class EventRange(
    val from: LocalDateTime,
    val endDay: LocalDate = from.date,
    val untilTime: LocalTime? = null,
    val endPending: Boolean = true,
) {
    /** The chosen end, or `null` while its time is still blank. */
    val until: LocalDateTime?
        get() = untilTime?.let { LocalDateTime(endDay, it) }
}

/**
 * What a range is bounded by: the latest end the caller accepts for a given start (the event window's
 * length). The caller owns that arithmetic — it knows the zone and the limit; this module knows neither.
 */
fun interface LatestUntil {
    fun of(from: LocalDateTime): LocalDateTime
}

/** Whether [time] on the chosen last day would be a valid end: after the start and within the window. */
internal fun EventRange.untilAllowed(time: LocalTime, latest: LatestUntil): Boolean {
    val until = LocalDateTime(endDay, time)
    return until > from && until <= latest.of(from)
}

/** Whether [time] on the start's day would be a valid start, given whatever end is already chosen. */
internal fun EventRange.fromAllowed(time: LocalTime, latest: LatestUntil): Boolean {
    val candidate = LocalDateTime(from.date, time)
    val chosenEnd = until
    return if (chosenEnd != null) {
        chosenEnd > candidate && chosenEnd <= latest.of(candidate)
    } else {
        endDay <= latest.of(candidate).date
    }
}

/**
 * The Until hour wheel settled on [hour]. From a blank end the minutes fill in as `:00`; otherwise they are
 * kept. A value that is not a valid end moves to the nearest one that is (an hour with no valid minute to
 * the nearest hour that has one), so a bad range is unreachable rather than refused.
 */
internal fun EventRange.settleUntilHour(hour: Int, latest: LatestUntil): EventRange =
    settleUntil(hour, untilTime?.minute ?: 0, latest)

/** The Until minute wheel settled on [minute]; from a blank end the hour is the one the wheel sat over. */
internal fun EventRange.settleUntilMinute(minute: Int, latest: LatestUntil): EventRange =
    settleUntil(untilTime?.hour ?: from.hour, minute, latest)

private fun EventRange.settleUntil(hour: Int, minute: Int, latest: LatestUntil): EventRange =
    nearestTime(hour, minute) { untilAllowed(it, latest) }?.let { copy(untilTime = it) } ?: this

/** The From hour wheel settled on [hour]; the minutes are kept where they stay valid. */
internal fun EventRange.settleFromHour(hour: Int, latest: LatestUntil): EventRange =
    settleFrom(hour, from.minute, latest)

/** The From minute wheel settled on [minute]. */
internal fun EventRange.settleFromMinute(minute: Int, latest: LatestUntil): EventRange =
    settleFrom(from.hour, minute, latest)

private fun EventRange.settleFrom(hour: Int, minute: Int, latest: LatestUntil): EventRange =
    nearestTime(hour, minute) { fromAllowed(it, latest) }?.let { copy(from = LocalDateTime(from.date, it)) } ?: this
