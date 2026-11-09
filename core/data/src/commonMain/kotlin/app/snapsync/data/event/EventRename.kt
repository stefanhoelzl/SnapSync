package app.snapsync.data.event

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ServiceFaults
import app.snapsync.data.crypto.MetaCipher
import app.snapsync.data.crypto.MetaOpening
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventName
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.NameVerdict
import app.snapsync.domain.service.ServiceFault
import app.snapsync.format.EncryptionText
import app.snapsync.format.MetaRecord
import app.snapsync.format.SealedMetaFormat
import app.snapsync.wire.api.ChangeEventReply
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.Closed
import app.snapsync.wire.api.EventNotFound
import app.snapsync.wire.api.GetEventReply
import app.snapsync.wire.api.Invalid
import app.snapsync.wire.api.Maintenance
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerIssue
import app.snapsync.wire.models.Event
import app.snapsync.wire.models.EventChange
import app.snapsync.wire.models.EventDetails

/**
 * Renames an event for every member (`changeEvent`). The name kept is the one the server answers it stored, never the
 * one sent: the server trims too, and its echo is the one way the two cannot disagree.
 *
 * An ENCRYPTED event's name is sealed (decision record `changes/seal-event-name`, D1/D6): the rename reads the record
 * the server holds, replaces its name — keeping every key this build does not know, so a newer build's field survives
 * — seals it again under the event's key and sends that. A legacy encrypted event, whose name is still readable, gets
 * its first record so. The echo is opened the same way.
 *
 * A "not found" is no answer like every other but a refused name: a lone "not found" is one witness that the event is
 * gone, and a rename is no road to its deletion.
 */
class EventRename(private val gateway: BackendGateway, private val cipher: MetaCipher) {

    /** Rename the plain event [event] to [name], readable. */
    suspend fun rename(event: EventId, name: EventName): RenameFact = sent(event, EventChange(name = name.text), null)

    /** Rename the encrypted event [event] to [name], sealed under its [key]. */
    suspend fun renameSealed(event: EventId, name: EventName, key: IdentifiedKey): RenameFact =
        when (val current = currentRecord(event, key)) {
            is Current.Record ->
                sent(event, EventChange(sealedMeta = cipher.seal(key, current.record.named(name.text))), key)
            is Current.Unanswered -> RenameFact.Unanswered(current.fault)
        }

    private suspend fun sent(event: EventId, change: EventChange, key: IdentifiedKey?): RenameFact =
        when (val reply = gateway.send(Routes.ChangeEvent.request(event.value, change))) {
            is ChangeEventReply.Changed -> stored(reply.event, key)
            is Invalid -> RenameFact.Refused
            // The server's positive word that the event's photos were deleted — unlike a lone "not found".
            is Closed -> RenameFact.EventOver
            is ClientError, is ServerIssue, is NoAnswer -> RenameFact.Unanswered(ServiceFaults.of(reply))
        }

    /** The record [event]'s name is in now, or a fresh one when the server still holds it readable. */
    private suspend fun currentRecord(event: EventId, key: IdentifiedKey): Current =
        when (val reply = gateway.send(Routes.GetEvent.request(event.value))) {
            is GetEventReply.Found -> recordOf(reply.eventDetails, key)
            // A lone "not found" is no deletion: told as no answer, like the rename's own.
            EventNotFound -> Current.Unanswered(null)
            is Maintenance -> Current.Unanswered(ServiceFault.UPDATING)
            is ClientError, is ServerIssue, is NoAnswer -> Current.Unanswered(ServiceFaults.of(reply))
        }

    /** [details]' record opened with [key]; a legacy event's readable name made its first record. */
    private fun recordOf(details: EventDetails, key: IdentifiedKey): Current {
        val sealed = details.sealedMeta ?: return Current.Record(SealedMetaFormat.recordNamed(details.name.orEmpty()))
        return opened(cipher.open(key, sealed))
    }

    private fun opened(opening: MetaOpening): Current = when (opening) {
        is MetaOpening.Opened -> Current.Record(opening.record)
        // The event's own key does not open what the server holds: no answer this build understands.
        is MetaOpening.Damaged -> Current.Unanswered(ServiceFault.UNREACHABLE)
    }

    private fun stored(echo: Event, key: IdentifiedKey?): RenameFact {
        val read = cipher.nameRead(echo.name, echo.sealedMeta, EncryptionText.parse(echo.keyId), key)
        return when (val name = NameVerdict.of(read, lastKnown = null)) {
            is NameVerdict.Named -> RenameFact.Renamed(name.name)
            // An answer the app does not understand.
            NameVerdict.Unreadable, NameVerdict.Withheld -> RenameFact.Unanswered(ServiceFault.UNREACHABLE)
        }
    }

    private sealed interface Current {
        class Record(val record: MetaRecord) : Current

        class Unanswered(val fault: ServiceFault?) : Current
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
