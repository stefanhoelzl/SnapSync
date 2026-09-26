package app.snapsync.model

/**
 * The **user-tap command bundle** (`docs/architecture.md`, "Commands cross one door"): the
 * commands the status screen can fire, so every user tap crosses the same door the OS-callback
 * triggers do. Seated in `model/` (migration step 9): the bundle is pure vocabulary — a record of
 * command callables with inert defaults — and `model/` is the one zone both `compose/` (which
 * builds the live instance) and `:domain:presentation` (which receives it by constructor) may name;
 * the armed presentation gate forbids presentation referencing `flow/`, and `flow/` itself may
 * keep importing `model/`. Live instances are still **built and decorated only in `compose/`**
 * (`AppCore.userCommands`) — presentation never references a feature command (or a flow callable)
 * directly, which is what the presentation gate enforces.
 *
 * Reads do NOT ride here: presentation observes feature read-model StateFlows directly (config,
 * permission, sync status, creation status, download progress), and the join gate's details fetch
 * (`StatusContainerHost`'s `loadJoinDetails`) stays an injected read — it returns a value the gate
 * reduces on, so it is a query, not a command.
 *
 * No field has a default (law "Function-typed parameters have no defaults in production", capability
 * `docs/architecture.md`): a host that builds this bundle states every command, so one it forgets does not
 * compile. They all used to default to inert, and a host that rebuilt the bundle by hand shipped a
 * "Choose more photos" button that did nothing.
 *
 * - [leave] — leave the configured event: cancel in-flight downloads, stop the producer, clear the
 *   config, notify the backend (capability `manage-membership`).
 * - [create] — mint a new event with a name and canonical UTC date **range** (`startsAt`, `endsAt`), then
 *   route it into the join gate (capability `create-event`). Fire-and-forget; outcomes arrive via
 *   `CreationStatusSource`.
 * - [commitJoin] — join (a bodyless membership write, no manifest) then provision the membership's
 *   capture-date **range** (`minPhotoDate`..`maxPhotoDate`, each clamped to the event window
 *   `startsAt`..`endsAt`), answering a [JoinCommit]: committed (incl. the already-joined no-op), at
 *   capacity, or failed (capability `join-event`).
 * - [share] — hand the invite URL to the platform share surface (fire-and-forget, `UiState` unaffected).
 * - [requestAccess] — raise the system photo-access dialog (capability `photo-access`): returns
 *   nothing and cannot suspend — the grant arrives only via the permission read-model.
 * - [openLink] — hand a URL to the platform to open outside the app. Its ONE caller is the
 *   update-required screen's App Store button (capability `app-update-required`), whose remedy is by
 *   definition not in this app. The URL is passed in rather than known here, because the screen's
 *   contract is that a build carrying no store URL renders no button — a command that knew the URL
 *   could not express that.
 * - [openSettings] — open the app's system Settings page (the `DENIED` affordance). Distinct from
 *   [reconfigure], which edits this *membership's* settings, not the iOS system settings page.
 * - [choosePhotos] — present the platform's limited-library picker (capability
 *   `photo-access`): the joined layer's "Choose more photos" affordance under a partial
 *   grant. Fire-and-forget; the resulting selection change arrives via the selection-change seam.
 * - [reconfigure] — change the joined membership's participation settings in place (direction, cutoff,
 *   album opt-in) without leaving (capability `manage-membership`). [eventId] is the event the
 *   settings surface was opened for; the use-case no-ops if the current membership no longer matches.
 *   Awaited: it answers a [ReconfigureOutcome], so a save that did not land is told to the member rather than
 *   closing the surface as if it had; the change itself lands via the config read-model.
 * - [rename] — rename the joined event for **every** member (capability `manage-membership`). [eventId] is
 *   the event the heading affordance was opened for; the use-case no-ops if the current membership no
 *   longer matches. Fire-and-forget; the outcome arrives via `RenameStatusSource`, and the new name
 *   lands via the config read-model. Unlike [reconfigure], which changes only this device's settings,
 *   this writes the shared event — but it is still just a command through the one door.
 * - [resetRename] — clear the rename status latch back to `Idle` once the screen has consumed a
 *   terminal value. Needed because `RenameStatus` carries a success value where `CreationStatus`
 *   deliberately does not: a rename changes no layer, so nothing else would clear it.
 * - [sendDiagnostics] — send this device's diagnostic dump to the operator's reporting channel, or keep it on
 *   the device on a build that reports nowhere (capability `privacy-security`), fired by the hidden double-tap
 *   once the operator has written
 *   what went wrong. `note` is that description, already trimmed and length-bounded by the sheet — it
 *   titles the report, so two reports about different problems arrive as different issues. `screen` is
 *   an opaque label for the surface it was sent from, supplied by the UI (the domain enumerates no
 *   screens); it is the only way a screen-local surface, which touches no port, reaches a report.
 *   Present on every build: where the report goes is [UiState.reportDestination], which the sheet states, so
 *   the affordance never suggests a destination the build does not have.
 */
/**
 * What a join commit did (capability `join-event`).
 *
 * A named outcome rather than a Boolean, because the two ways to fail need DIFFERENT screens and a
 * Boolean cannot carry the difference. [Failed] is transient — the network, the backend, the moment —
 * and the surface it produces offers a Retry that genuinely may work. [Full] is not: the event is at
 * capacity, retrying fails identically, and a Retry button there is an invitation to press it forever.
 * Collapsing them left a member tapping Retry against a wall with nothing telling them what the wall
 * was.
 */
enum class JoinCommit {
    /** The membership is committed (a re-join of the same event counts — it is already committed). */
    Committed,

    /** The event is at capacity; no retry can change that. */
    Full,

    /** The commit did not land, for a reason that may not hold next time. */
    Failed,
}

class UserCommands(
    val leave: suspend () -> Unit,
    val create: (name: String, startsAt: EventStart, endsAt: EventEnd) -> Unit,
    val commitJoin: suspend (
        eventId: String,
        name: String,
        startsAt: EventStart,
        endsAt: EventEnd,
        deletesAt: DeletesAt,
        minPhotoDate: CaptureCutoff,
        maxPhotoDate: CaptureCeiling,
        direction: Direction,
        saveToAlbum: Boolean,
    ) -> JoinCommit,
    val share: (String) -> Unit,
    val requestAccess: () -> Unit,
    val openSettings: () -> Unit,
    val openLink: (url: String) -> Unit,
    val choosePhotos: () -> Unit,
    val reconfigure: suspend (
        eventId: String,
        direction: Direction,
        minPhotoDate: CaptureCutoff,
        maxPhotoDate: CaptureCeiling,
        saveToAlbum: Boolean,
    ) -> ReconfigureOutcome,
    val rename: (eventId: String, name: String) -> Unit,
    /**
     * Suspending, unlike the other latch-driven commands: the screen fires this **after** consuming a
     * terminal status and may start the next rename immediately, so the clear has to have happened by
     * the time the call returns. Detaching it opened a window where a second rename began with the
     * previous `Succeeded` still latched.
     */
    val resetRename: suspend () -> Unit,
    val sendDiagnostics: suspend (note: String, screen: String) -> Unit,
)
