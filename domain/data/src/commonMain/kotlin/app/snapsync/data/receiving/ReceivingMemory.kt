package app.snapsync.data.receiving

import app.snapsync.domain.event.EventId
import app.snapsync.domain.receiving.ReceivedReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the backend recorded of this device's received reports, per event, in THIS process — so an unchanged one is not
 * sent again. Process memory on purpose: the backend keeps the highest position and the latest word on receiving
 * nothing, so a new process repeating one already recorded costs one request and changes nothing.
 */
class ReportedReceived {
    private val recorded = MutableStateFlow<Map<EventId, ReceivedReport>>(emptyMap())

    /** The last report the backend recorded for [event] in this process, or `null` for none. */
    fun last(event: EventId): ReceivedReport? = recorded.value[event]

    /** The backend recorded [report] for [event]. */
    fun recorded(event: EventId, report: ReceivedReport) {
        recorded.update { it + (event to report) }
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
