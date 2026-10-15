package app.snapsync.domain.event

import kotlin.jvm.JvmInline

/**
 * An event's name: trimmed, never blank, at most [MAX_LENGTH] characters — the backend's rule, so a name that exists
 * is one the backend would take. [of] is the one way in.
 */
@JvmInline
value class EventName private constructor(val text: String) {
    companion object {
        /** The longest name the backend takes. */
        const val MAX_LENGTH: Int = 100

        /** [raw] as a name, or why it is none. */
        fun of(raw: String): EventNameVerdict {
            val trimmed = raw.trim()
            return when {
                trimmed.isEmpty() -> EventNameVerdict.Blank
                trimmed.length > MAX_LENGTH -> EventNameVerdict.TooLong
                else -> EventNameVerdict.Ok(EventName(trimmed))
            }
        }
    }
}

/** Whether a text is an [EventName], and why not when it is not. */
sealed interface EventNameVerdict {
    class Ok(val name: EventName) : EventNameVerdict

    /** Nothing but whitespace. */
    data object Blank : EventNameVerdict

    /** Longer than [EventName.MAX_LENGTH]. */
    data object TooLong : EventNameVerdict
}
