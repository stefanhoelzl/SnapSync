package app.snapsync.domain.event

import app.snapsync.domain.service.ServiceFault

/** What a create the member asked for came to. */
sealed interface CreateResult {
    /** The service minted [eventId]; [key] is its key when the event is encrypted — the creator joins with it. */
    class Created(val eventId: EventId, val key: EventKey?) : CreateResult

    /** The service refused the name. */
    data object InvalidName : CreateResult

    /**
     * The service refused the date range: an end not after the start, or a window longer than the deployment allows.
     * The create screen's picker cannot produce either, so this arrives only when the backend's limit moved after this
     * build was made — and then it must not read as a refused name.
     */
    data object InvalidWindow : CreateResult

    /** The service accepts no credential this phone holds; why it was refused is the attestation's verdict to say. */
    data object Unverified : CreateResult

    /** No answer the create could use: [fault] is how the service failed. */
    data class Failed(val fault: ServiceFault) : CreateResult
}
