package app.snapsync.feature.membership

import app.snapsync.domain.event.EventId
import app.snapsync.model.UnionPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * The event union one provision has already read, handed from the membership entry's adoption to the provision's
 * download reconcile, so a join reads the union once. Both run inside one provision,
 * seconds apart, and ask the same question: a second read would only repeat the answer.
 *
 * Held only while a join is [open], and only for the event it provisions: outside one, an offer is dropped and a take
 * answers `null`, so no later trigger — a push, an opening — ever plans from a union it did not read. A take answers
 * the union once. Nothing offered (the adoption's read failed or timed out, or a re-provision of the joined event ran
 * no adoption) leaves the reconcile to read its own.
 */
class JoinUnion {
    private class Held(val eventId: EventId, val page: UnionPage? = null)

    private val held = MutableStateFlow<Held?>(null)

    /** A join of [eventId] began: an offer of its union is held from now on. */
    fun open(eventId: EventId) {
        held.value = Held(eventId)
    }

    /** The join ended, however it did: nothing is held any more. */
    fun close() {
        held.value = null
    }

    fun offer(eventId: EventId, page: UnionPage) =
        held.update { current -> if (current?.eventId == eventId) Held(eventId, page) else current }

    fun take(eventId: EventId): UnionPage? =
        held.getAndUpdate { current -> if (current?.eventId == eventId) Held(eventId) else current }
            ?.takeIf { it.eventId == eventId }?.page
}
