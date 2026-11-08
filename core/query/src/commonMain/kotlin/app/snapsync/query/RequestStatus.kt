package app.snapsync.query

import app.snapsync.domain.request.RequestId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A member's latest request of one kind — a join confirmed, settings saved, a rename — as it stands: none yet, running,
 * or answered. Keyed by the request's id, so an earlier attempt's answer never answers a later one: a reader acts only
 * on the answer to its own request.
 */
sealed interface RequestStatus<out T> {
    data object Idle : RequestStatus<Nothing>

    data class InFlight(val id: RequestId) : RequestStatus<Nothing>

    data class Done<out T>(val id: RequestId, val value: T) : RequestStatus<T>
}

/** Where a request's progress is written — the work that answers it. */
interface RequestStatusSink<in T> {
    /** [id] was asked for and is running. */
    fun begun(id: RequestId)

    /** [id] is answered with [value]. */
    fun done(id: RequestId, value: T)

    /** [id] is answered with [value] — unless it was answered already: what a run that ended early answers. */
    fun settle(id: RequestId, value: T)
}

/** One kind of request's status: [status] to read, and its sink to write. */
class RequestStatuses<T> : RequestStatusSink<T> {
    private val held = MutableStateFlow<RequestStatus<T>>(RequestStatus.Idle)

    val status: StateFlow<RequestStatus<T>> = held.asStateFlow()

    override fun begun(id: RequestId) {
        held.value = RequestStatus.InFlight(id)
    }

    override fun done(id: RequestId, value: T) {
        held.value = RequestStatus.Done(id, value)
    }

    override fun settle(id: RequestId, value: T) {
        held.compareAndSet(RequestStatus.InFlight(id), RequestStatus.Done(id, value))
    }
}
