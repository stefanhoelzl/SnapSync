package app.snapsync.ui.components

import kotlinx.datetime.LocalTime
import kotlin.math.abs

// Searching a day's times for an allowed one — what turns a wheel's settle on a disallowed row into the
// nearest valid value (capability `create-event`: a bad range is unreachable, never refused).

private const val HOURS_PER_DAY = 24
private const val MINUTES_PER_HOUR = 60

/**
 * The allowed time nearest to [hour]:[minute] — the nearest hour that has any allowed minute, then the
 * nearest allowed minute within it — or `null` when no time of day is allowed at all.
 */
internal fun nearestTime(hour: Int, minute: Int, allowed: (LocalTime) -> Boolean): LocalTime? =
    nearestHour(hour, allowed)?.let { found ->
        (0 until MINUTES_PER_HOUR).sortedBy { abs(it - minute) }.map { LocalTime(found, it) }.first(allowed)
    }

/** The hour nearest to [hour] that has any allowed minute, or `null` when no time of day is allowed. */
internal fun nearestHour(hour: Int, allowed: (LocalTime) -> Boolean): Int? =
    (0 until HOURS_PER_DAY).sortedBy { abs(it - hour) }.firstOrNull { hourHasAllowedMinute(it, allowed) }

/** Whether any minute of [hour] is allowed — what decides if an hour row can be settled on. */
internal fun hourHasAllowedMinute(hour: Int, allowed: (LocalTime) -> Boolean): Boolean =
    (0 until MINUTES_PER_HOUR).any { allowed(LocalTime(hour, it)) }
