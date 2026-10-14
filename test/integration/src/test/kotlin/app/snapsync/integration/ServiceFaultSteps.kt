package app.snapsync.integration

import app.snapsync.control.behaviour.Acts
import app.snapsync.levers.BackendFault
import app.snapsync.ui.state.UiIntent

// The steps a behaviour about a failing service takes (decision record `changes/tell-service-faults`): the service
// failing every request the app sends, working again, and the create a failed one is retried by.

/** Create tapped again over the form as it stands — the retry a failed create offers, nothing retyped — and, for a range
 *  already over, the question of how long others may join answered with [openUntilDays] (left open without it). */
suspend fun Acts.createAgain(openUntilDays: Int? = null) {
    tap(listOfNotNull(UiIntent.ConfirmCreate, openUntilDays?.let { UiIntent.KeepOpenFor(it) }))
}

/** Every operation of the device API this app sends itself — what an outage of SnapSync's service fails. */
val APP_OPERATIONS = listOf(
    "challenge", "mintToken", "renewToken", "createEvent", "getEvent", "changeEvent", "joinEvent", "leaveEvent",
    "publishManifest", "reportReceived", "eventFiles", "deviceFiles", "uploadState", "putDevice",
)

/** SnapSync's service fails every request the app sends, as [fault] says, until it [recovers]. */
suspend fun Acts.outage(fault: BackendFault) {
    APP_OPERATIONS.forEach { backend.fail(it, fault, OUTAGE_LENGTH) }
}

/** SnapSync's service works again: every fault an outage set is lifted. */
suspend fun Acts.recovers() = backend.recover()

/** More requests than any behaviour sends: an outage that lasts until it is lifted. */
private const val OUTAGE_LENGTH = 10_000
