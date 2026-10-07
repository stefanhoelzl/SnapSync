package app.snapsync.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Where the joined event is in its life, as the joined screen's dates line says it (capability `sync-status`,
 * "The joined screen shows how long the event lasts"): how long until it starts, how long until it ends, or
 * that it has ended.
 *
 * Reduced from the membership's bounds and the host's minute tick, so the screen formats words and never
 * reads a clock. The tick already runs until both bounds have passed, which is exactly the lifetime of a
 * phrase that can still change: once [Ended], nothing here moves again.
 */
@Serializable
sealed interface EventTiming {
    /** Before the start: [remaining] until it. */
    @Serializable
    data class Upcoming(val remaining: TimeLeft) : EventTiming

    /**
     * Between the start and the end: [remaining] until the end — `null` only in the placeholder state before the
     * first reduction ([UiState]'s default), which says nothing about an end it does not know.
     */
    @Serializable
    data class Running(val remaining: TimeLeft?) : EventTiming

    /** The event's range has ended. Informational: sync carries on (capability `event-lifetime`). */
    @Serializable
    data object Ended : EventTiming
}

/**
 * A span of time as ONE whole unit, floored: days while a day or more remains, then hours, then minutes.
 * Never weeks — an event lasts at most 30 days, and "ends in 29 days" reads plainer than "in 4 weeks".
 */
@Serializable
sealed interface TimeLeft {
    @Serializable
    data class Days(val count: Int) : TimeLeft

    @Serializable
    data class Hours(val count: Int) : TimeLeft

    @Serializable
    data class Minutes(val count: Int) : TimeLeft

    @Serializable
    data object UnderAMinute : TimeLeft

    companion object {
        /** The span floored to its largest whole unit. */
        fun of(span: Duration): TimeLeft = when {
            span >= 1.days -> Days(span.inWholeDays.toInt())
            span >= 1.hours -> Hours(span.inWholeHours.toInt())
            span >= 1.minutes -> Minutes(span.inWholeMinutes.toInt())
            else -> UnderAMinute
        }
    }
}

/**
 * The timing of an event bounded by [startsAt] and [endsAt] at [now]. The start is inclusive (the event has
 * begun AT its start) and so is the end: an event is [EventTiming.Ended] only once `now` is past [endsAt],
 * the same comparison the ended marker always made.
 */
fun eventTiming(startsAt: EventStart, endsAt: EventEnd, now: CaptureDate): EventTiming {
    val at = Instant.parse(now.iso)
    return when {
        startsAt.at > now -> EventTiming.Upcoming(TimeLeft.of(Instant.parse(startsAt.at.iso) - at))
        endsAt.at < now -> EventTiming.Ended
        else -> EventTiming.Running(TimeLeft.of(Instant.parse(endsAt.at.iso) - at))
    }
}
