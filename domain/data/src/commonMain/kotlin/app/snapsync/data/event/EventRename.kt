package app.snapsync.data.event

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.faultOf
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventName
import app.snapsync.domain.event.EventNameVerdict
import app.snapsync.domain.service.ServiceFault
import app.snapsync.wire.api.ChangeEventReply
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.Closed
import app.snapsync.wire.api.Invalid
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerIssue
import app.snapsync.wire.models.EventChange

/**
 * Renames an event for every member (`changeEvent`). The name kept is the one the server answers it stored, never the
 * one sent: the server trims too, and its echo is the one way the two cannot disagree.
 *
 * A "not found" is no answer like every other but a refused name: a lone "not found" is one witness that the event is
 * gone, and a rename is no road to its deletion.
 */
class EventRename(private val gateway: BackendGateway) {

    /** Rename [event] to [name]. */
    suspend fun rename(event: EventId, name: EventName): RenameFact =
        when (val reply = gateway.send(Routes.ChangeEvent.request(event.value, EventChange(name = name.text)))) {
            is ChangeEventReply.Changed -> stored(reply.event.name)
            is Invalid -> RenameFact.Refused
            // The server's positive word that the event's photos were deleted — unlike a lone "not found".
            is Closed -> RenameFact.EventOver
            is ClientError, is ServerIssue, is NoAnswer -> RenameFact.Unanswered(faultOf(reply))
        }

    private fun stored(text: String): RenameFact = when (val name = EventName.of(text)) {
        is EventNameVerdict.Ok -> RenameFact.Renamed(name.name)
        // An answer the app does not understand.
        EventNameVerdict.Blank, EventNameVerdict.TooLong -> RenameFact.Unanswered(ServiceFault.UNREACHABLE)
    }
}

/** What the server answered a rename. */
sealed interface RenameFact {
    /** Renamed: [name] is what the server stored. */
    data class Renamed(val name: EventName) : RenameFact

    /** Nothing was renamed, and the event goes on. */
    sealed interface NotRenamed : RenameFact

    /** The server refused the name. */
    data object Refused : NotRenamed

    /** The event's photos are deleted: it is over. */
    data object EventOver : RenameFact

    /**
     * No answer could be had, or one that could not be read. [fault] is how the service failed — `null` for an answer
     * from a working one, a "not found" among them.
     */
    data class Unanswered(val fault: ServiceFault?) : NotRenamed
}
