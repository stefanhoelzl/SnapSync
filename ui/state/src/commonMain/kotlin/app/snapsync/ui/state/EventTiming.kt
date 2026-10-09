package app.snapsync.ui.state

import kotlinx.serialization.Serializable

/**
 * Where the joined event is in its life, as the joined screen's dates line says it: how long until it starts, how long
 * until it ends, or that it has ended.
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

    /** The event's range has ended. Informational: sync carries on. */
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
}
