package app.snapsync.model

import co.touchlab.kermit.Logger

/**
 * What became of something this app handed to the platform (the `SystemUi` port).
 *
 * The app ACTS on neither answer — nothing in `UiState` depends on one — but it records both, because they
 * have different consequences (`docs/architecture.md`, "Absence is never silent"): after [Accepted] the
 * user is elsewhere and it is not this app's business; after [Refused] the user is still here, and the tap
 * they made did nothing they can see.
 */
sealed interface Handoff {
    /** The platform took it: the sheet is on screen, or the app claiming the URL is. */
    data object Accepted : Handoff

    /** Nothing was handed off. [reason] is for the log, in the adapter's own words. */
    data class Refused(val reason: String) : Handoff
}

/** Whether something the platform guards can be reached right now — a read every platform can always make. */
enum class Availability { AVAILABLE, UNAVAILABLE }

/**
 * Records a hand-off to the platform that did not happen, and answers it unchanged. Nothing acts on a [Handoff], but a
 * refusal is logged at `Error` by [log] as [name]'s, because the user then tapped and nothing happened — on the
 * update-required screen, to the only remedy the screen offers (`docs/architecture.md`, "Absence is never silent").
 * `Error` is what reaches the operator from a production build.
 */
fun Handoff.recordingRefusal(log: Logger, name: String): Handoff = also {
    if (it is Handoff.Refused) log.e { "$name: nothing was handed off — ${it.reason}" }
}
