package app.snapsync.domain.membership

import app.snapsync.domain.event.Event
import app.snapsync.domain.event.EventLookupFact
import app.snapsync.domain.time.Timestamp

/**
 * Whether the server's answer about a joined event confirms its photos deleted. Marking a membership deleted destroys
 * nothing — it stays, shown as over — but it stops every transfer, so the confirmation needs a witness no backend fault
 * can manufacture: the server's positive word that the event completed, or its "not found" once this membership's OWN
 * deadline has passed, a date this device stored at its join. A "not found" before that is disbelieved.
 */
sealed interface DeletionVerdict {
    class Confirmed(val evidence: ConfirmedDeletion) : DeletionVerdict

    /** The event is on the server, its photos with it, as [served]. */
    data class NotDeleted(val served: Event) : DeletionVerdict

    /** Nothing can be told: no answer, or a "not found" before the deadline. */
    data object Inconclusive : DeletionVerdict

    companion object {
        /** What [lookup], answered at [now], says of [event]. */
        @OptIn(MintsConfirmedDeletion::class)
        fun of(event: Event, lookup: EventLookupFact, now: Timestamp): DeletionVerdict = when (lookup) {
            is EventLookupFact.Found -> when (lookup.completed) {
                true -> Confirmed(ConfirmedDeletion(event.id))
                false -> NotDeleted(lookup.event)
            }
            EventLookupFact.NotFound -> when (now > event.deletesAt.at) {
                true -> Confirmed(ConfirmedDeletion(event.id))
                false -> Inconclusive
            }
            is EventLookupFact.Unanswered -> Inconclusive
        }

        /**
         * The server answered a write for [event] — a rename, the shared set — that its photos were deleted: its
         * positive word, as a completed event read is.
         */
        @OptIn(MintsConfirmedDeletion::class)
        fun answeredOver(event: Event): Confirmed = Confirmed(ConfirmedDeletion(event.id))
    }
}
