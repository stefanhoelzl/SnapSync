package app.snapsync.application.event

import app.snapsync.application.EntryPointCall
import app.snapsync.data.crypto.NewEventKey
import app.snapsync.data.crypto.NewEventKeys
import app.snapsync.data.event.EventCreation
import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.event.EventName
import app.snapsync.domain.event.EventNameVerdict
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.request.CreateRequest
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log

private val log = Log("CreateEvent")

/**
 * **Create an event**: its key minted here, on the creating device, when the event is encrypted — only the key's id is
 * sent (`docs/architecture.md`, the encrypted file format) — and the event minted. The minted event's id and key are
 * the answer: the creator joins through the same gate as every guest, with the key its own invite carries. One at a
 * time is the screen's to hold: a second create minted while the first is out is a second event.
 */
class CreateEvent(private val creation: EventCreation, private val keys: NewEventKeys) {
    @EntryPointCall
    suspend operator fun invoke(request: CreateRequest): CreateResult = when (EventName.of(request.name)) {
        is EventNameVerdict.Ok -> created(request)
        // The name rule is this device's to apply: an encrypted event's name goes sealed, which the service cannot
        // check (decision record `changes/seal-event-name`, D3) — so a name that is none is refused before anything
        // is minted or sent, for every event alike.
        EventNameVerdict.Blank, EventNameVerdict.TooLong ->
            CreateResult.InvalidName.also { log.i("refused", "result" of "InvalidName") }
    }

    private suspend fun created(request: CreateRequest): CreateResult {
        val key = newKey()
        return creation.create(request, key)
            .also { log.i("created", "result" of it::class.simpleName, "encrypted" of (key != null)) }
    }

    /** The new event's key: minted unless this build creates plain events. */
    private fun newKey(): IdentifiedKey? = when (val new = keys.forNewEvent()) {
        NewEventKey.Plain -> null
        is NewEventKey.Encrypted -> new.key
    }
}
