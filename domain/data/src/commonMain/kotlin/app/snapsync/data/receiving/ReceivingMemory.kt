package app.snapsync.data.receiving

import app.snapsync.domain.event.EventId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The received positions the backend recorded, per event, in THIS process — so an unchanged position is not sent again.
 * Process memory on purpose: the backend keeps the highest, so a new process reporting one already recorded costs one
 * request and changes nothing.
 */
class ReportedPositions {
    private val recorded = MutableStateFlow<Map<EventId, Long>>(emptyMap())

    /** The last position the backend recorded for [event] in this process, or `null` for none. */
    fun last(event: EventId): Long? = recorded.value[event]

    /** The backend recorded [position] for [event]. */
    fun recorded(event: EventId, position: Long) {
        recorded.update { it + (event to position) }
    }
}

/**
 * The membership whose library a recognition pass has read in THIS process — so the import drain and a usable grant
 * ask for a pass only until one has run, and a selection change that may have brought unrecognised photos asks again.
 * Process memory on purpose: a new process has read nothing, and its first drain reads the library once more.
 */
class RecognitionPasses {
    private val read = MutableStateFlow<EventId?>(null)

    /** Whether [event]'s library has been read by a pass in this process. */
    fun ranFor(event: EventId): Boolean = read.value == event

    /** A pass read [event]'s library. */
    fun ran(event: EventId) {
        read.value = event
    }

    /** Ask again: what was read is no longer the whole story. */
    fun forget() {
        read.value = null
    }
}
