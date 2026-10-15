package app.snapsync.domain.event

import app.snapsync.domain.service.ServiceFault

/** What the server answered about a joined event, as far as a membership cares. */
sealed interface EventLookupFact {

    /** The event exists, as [event]. [completed] is the server's positive word that its photos are deleted. */
    data class Found(val event: Event, val completed: Boolean) : EventLookupFact

    /** The server holds no such event — which alone proves nothing: a missing row is no deletion. */
    data object NotFound : EventLookupFact

    /**
     * No answer could be had: offline, a failed request, a reply that could not be read. [fault] is how the service
     * failed, as the member is told it; [retryAfterSeconds], while it is being updated, how long it suggests waiting.
     */
    data class Unanswered(val fault: ServiceFault, val retryAfterSeconds: Long? = null) : EventLookupFact
}
