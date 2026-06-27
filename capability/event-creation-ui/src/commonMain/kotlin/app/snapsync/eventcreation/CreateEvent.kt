package app.snapsync.eventcreation

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The create-event use-case (the `JoinEvent` twin): mint an event, then provision it exactly like a
 * scanned QR. `create(name)` is fire-and-forget — it launches on the injected [scope], sets
 * [CreationStatus.InFlight], calls the backend via [client] with the trimmed name, and:
 * - on [CreateOutcome.Created], funnels the returned `eventId` into the **existing** provision path
 *   ([provision] — `onProvision` + `ConfigStore.save` + reconcile, supplied by the composition root)
 *   and returns the status to [CreationStatus.Idle] (config is now present, so the reduction has
 *   already left the create layer);
 * - on failure, sets [CreationStatus.Failed] with the matching reason and does **not** provision.
 *
 * It never inspects `PermissionStatus`: a missing grant surfaces afterward via the existing
 * `PermissionBlocked` path once config is present.
 */
class CreateEvent(
    private val client: EventCreationClient,
    private val status: MutableCreationStatusSource,
    private val provision: suspend (eventId: String) -> Unit,
    private val scope: CoroutineScope,
) : EventCreator {

    private val log = Logger.withTag("CreateEvent")

    override fun create(name: String) {
        scope.launch {
            status.set(CreationStatus.InFlight)
            when (val outcome = client.create(name.trim())) {
                is CreateOutcome.Created -> {
                    provision(outcome.eventId)
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
