package app.snapsync.data.push

import app.snapsync.domain.event.EventId
import app.snapsync.format.PushPayloadText
import app.snapsync.ports.PushMessage

/** **What a silent push the push port delivered names**: its event and the union position it announces. */
object PushPayload {
    /** The event [message] names, `null` when it names none ([PushPayloadText.eventId]). */
    fun eventId(message: PushMessage): EventId? = PushPayloadText.eventId(message.payload)

    /** The union position [message] announces, `null` when it names none ([PushPayloadText.seq]). */
    fun seq(message: PushMessage): Long? = PushPayloadText.seq(message.payload)
}
