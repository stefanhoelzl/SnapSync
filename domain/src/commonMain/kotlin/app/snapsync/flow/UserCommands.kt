package app.snapsync.flow

import app.snapsync.model.Direction

/**
 * The **user-tap command bundle** (spec `module-architecture`, "Commands cross one door"): the
 * commands the status screen can fire, defined here in `flow/` so every user tap crosses the same
 * door the OS-callback triggers do. Instances are **built and decorated only in `compose/`**
 * (`AppCore.userCommands`) and injected into presentation by constructor — presentation never
 * references a feature command (or a flow callable) directly, which is what the step-9 presentation
 * gate will enforce.
 *
 * Reads do NOT ride here: presentation observes feature read-model StateFlows directly (config,
 * permission, sync status, creation status, download progress), and the join gate's details fetch
 * ([app.snapsync.presentation.StatusContainerHost]'s `loadJoinDetails`) stays an injected read — it
 * returns a value the gate reduces on, so it is a query, not a command.
 *
 * Every field defaults to inert (the same no-op defaults the individual constructor lambdas carried),
 * so non-iOS hosts and tests that don't exercise a command construct unchanged.
 *
 * - [leave] — leave the configured event: cancel in-flight downloads, stop the producer, clear the
 *   config, notify the backend (capability `leave-event`).
 * - [create] — mint a new event with a name and canonical UTC start, then route it into the join gate
 *   (capability `event-creation-ui`). Fire-and-forget; outcomes arrive via `CreationStatusSource`.
 * - [commitJoin] — enroll (register-only empty manifest) then provision, returning `true` when joined
 *   (incl. the already-joined no-op) and `false` on a failed enrollment (capability `join-event`).
 * - [share] — hand the invite URL to the platform share surface (fire-and-forget, `UiState` unaffected).
 */
class UserCommands(
    val leave: suspend () -> Unit = {},
    val create: (name: String, startsAt: String) -> Unit = { _, _ -> },
    val commitJoin: suspend (
        eventId: String,
        name: String,
        startsAt: String,
        minPhotoDate: String,
        direction: Direction,
        saveToAlbum: Boolean,
    ) -> Boolean = { _, _, _, _, _, _ -> false },
    val share: (String) -> Unit = {},
)
