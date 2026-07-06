package app.snapsync.eventcreation

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The create-event use-case: mint an event, then route it into the **same** join gate a scanned QR
 * uses (capability `join-event`). `create(name)` is fire-and-forget — it launches on the injected
 * [scope], sets [CreationStatus.InFlight], calls the backend via [client] with the trimmed name, and:
 * - on [CreateOutcome.Created], hands the returned `eventId` to [onMinted] (the composition root routes
 *   it into the pending-join gate, non-auto-confirmed — the creator loads the event, picks a
 *   capture-date cutoff, and confirms like any joiner) and returns the status to [CreationStatus.Idle]
 *   (the pending join now drives the reduction — see `event-creation-ui` / `photo-date-cutoff`);
 * - on failure, sets [CreationStatus.Failed] with the matching reason and opens no gate.
 *
 * It never inspects `PermissionStatus`: a missing grant surfaces afterward via the existing
 * `PermissionBlocked` path once config is present.
 */
class CreateEvent(
    private val client: EventCreationClient,
    private val status: MutableCreationStatusSource,
    // Route the minted event into the join gate (the composition root binds this to the container's
    // `onEventCreated`). The `POST /events` already minted the event, so the gate holds a real id and
    // performs a real details load; provision (save config with name + cutoff) happens on confirm.
    private val onMinted: suspend (eventId: String) -> Unit,
    private val scope: CoroutineScope,
) : EventCreator {

    private val log = Logger.withTag("CreateEvent")

    override fun create(name: String) {
        scope.launch {
            status.set(CreationStatus.InFlight)
            when (val outcome = client.create(name.trim())) {
                is CreateOutcome.Created -> {
                    onMinted(outcome.eventId)
                    status.set(CreationStatus.Idle)
                }
                CreateOutcome.InvalidName -> {
                    log.i { "create rejected: invalid name" }
                    status.set(CreationStatus.Failed(CreationFailureReason.INVALID_NAME))
                }
                CreateOutcome.Transient -> {
                    log.i { "create failed: transient/server error" }
                    status.set(CreationStatus.Failed(CreationFailureReason.SERVER))
                }
            }
        }
    }
}
