package app.snapsync.model

/**
 * The **user-tap command bundle** (spec `module-architecture`, "Commands cross one door"): the
 * commands the status screen can fire, so every user tap crosses the same door the OS-callback
 * triggers do. Seated in `model/` (migration step 9): the bundle is pure vocabulary — a record of
 * command callables with inert defaults — and `model/` is the one zone both `compose/` (which
 * builds the live instance) and `:ui:presentation` (which receives it by constructor) may name;
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
 * Every field defaults to inert (the same no-op defaults the individual constructor lambdas carried),
 * so non-iOS hosts and tests that don't exercise a command construct unchanged.
 *
 * - [leave] — leave the configured event: cancel in-flight downloads, stop the producer, clear the
 *   config, notify the backend (capability `leave-event`).
 * - [create] — mint a new event with a name and canonical UTC date **range** (`startsAt`, `endsAt`), then
 *   route it into the join gate (capability `event-creation-ui`). Fire-and-forget; outcomes arrive via
 *   `CreationStatusSource`.
 * - [commitJoin] — enroll (register-only empty manifest) then provision the membership's capture-date
 *   **range** (`minPhotoDate`..`maxPhotoDate`, each clamped to the event window `startsAt`..`endsAt`),
 *   returning `true` when joined (incl. the already-joined no-op) and `false` on a failed enrollment
 *   (capability `join-event`).
 * - [share] — hand the invite URL to the platform share surface (fire-and-forget, `UiState` unaffected).
 * - [requestAccess] — raise the system photo-access dialog (capability `permission-gate`): returns
 *   nothing and cannot suspend — the grant arrives only via the permission read-model.
 * - [openSettings] — open the app's system Settings page (the `DENIED` affordance). Distinct from
 *   [reconfigure], which edits this *membership's* settings, not the iOS system settings page.
 * - [choosePhotos] — present the platform's limited-library picker (capability
 *   `limited-photo-access`): the joined layer's "Choose more photos" affordance under a partial
 *   grant. Fire-and-forget; the resulting selection change arrives via the selection-change seam.
 * - [reconfigure] — change the joined membership's participation settings in place (direction, cutoff,
 *   album opt-in) without leaving (capability `reconfigure-membership`). [eventId] is the event the
 *   settings surface was opened for; the use-case no-ops if the current membership no longer matches.
 *   Fire-and-forget; the change lands via the config read-model on the next cycle.
 */
class UserCommands(
    val leave: suspend () -> Unit = {},
    val create: (name: String, startsAt: EventStart, endsAt: EventEnd) -> Unit = { _, _, _ -> },
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
    ) -> Boolean = { _, _, _, _, _, _, _, _, _ -> false },
    val share: (String) -> Unit = {},
    val requestAccess: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val choosePhotos: () -> Unit = {},
    val reconfigure: suspend (
        eventId: String,
        direction: Direction,
        minPhotoDate: CaptureCutoff,
        maxPhotoDate: CaptureCeiling?,
        saveToAlbum: Boolean,
    ) -> Unit = { _, _, _, _, _ -> },
)
