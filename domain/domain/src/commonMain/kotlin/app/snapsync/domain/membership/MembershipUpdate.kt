package app.snapsync.domain.membership

import app.snapsync.domain.event.Event

/**
 * What a found event's answer changes in the event this device holds: its name, its members and its open-until date
 * follow the server — one the server did not serve keeps the last known.
 */
sealed interface MembershipUpdate {
    data class Changed(val event: Event) : MembershipUpdate

    data object Unchanged : MembershipUpdate

    companion object {
        /** [event] as the server [served] it now. */
        fun of(event: Event, served: Event): MembershipUpdate {
            val next = event.copy(
                name = served.name,
                members = served.members ?: event.members,
                openUntil = served.openUntil ?: event.openUntil,
            )
            return when (next == event) {
                true -> Unchanged
                false -> Changed(next)
            }
        }
    }
}
