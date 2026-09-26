package app.snapsync.model

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

/**
 * A yes/no the platform may be unable to give (`docs/architecture.md`, "A read that can be unknown returns a sealed
 * result"): [UNKNOWN] is its own answer, never folded into either of the others.
 */
enum class Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }
