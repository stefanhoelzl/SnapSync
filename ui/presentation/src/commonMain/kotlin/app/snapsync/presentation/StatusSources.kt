package app.snapsync.presentation

import app.snapsync.feature.creation.CreationStatusSource
import app.snapsync.feature.creation.MutableCreationStatusSource
import app.snapsync.feature.download.DownloadProgress
import app.snapsync.feature.download.DownloadStatusSource
import app.snapsync.feature.download.InMemoryDownloadStatusSource
import app.snapsync.feature.membership.MutableRenameStatusSource
import app.snapsync.feature.membership.RenameStatusSource
import app.snapsync.feature.status.SyncStatusSource
import app.snapsync.model.EventConfig
import app.snapsync.model.PermissionStatus
import app.snapsync.feature.version.AppVersionGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Every read-model [StatusContainerHost] reduces over, in one bundle.
 *
 * These are grouped because they are the same KIND of thing, not to shorten a signature: each is a
 * value the container OBSERVES and folds into `UiState`, and none of them is invoked. What the
 * container invokes (the command bundle, the join-details query) and what it emits (the diagnostics
 * seams, [StatusDiagnostics]) stay separate for that reason — a bundle that mixed them would group by
 * arity rather than by meaning, and the next reader would have to open it to learn what it is.
 *
 * Presentation observes read-model `StateFlow`s directly and never names `ports/` (spec
 * `module-architecture`, "Commands cross one door"); the armed presentation gate enforces it, and the
 * shell or the harness passes each flow in. The defaults are all **inert** so a host that does not
 * exercise an arm — the forge reviewing a forged state, a test that only drives the join gate —
 * constructs without naming it.
 */
class StatusSources(
    val sync: SyncStatusSource,
    /**
     * The photo-permission read-model. Observed for the health rung, and additionally READ at its
     * current value the moment a details load resolves, to decide whether the join gate shows the
     * photo-access explainer (capability `join-event`). That read is a snapshot, not an observation —
     * the phase advances only by user action.
     */
    val permission: StateFlow<PermissionStatus>,
    /** The persisted membership. Config presence is the reduction's top rung. */
    val config: StateFlow<EventConfig?>,
    /**
     * The create-status read-model. Inert by default (always `Idle`) so a host that never creates
     * constructs unchanged; the iOS shell injects the instance the create use-case drives.
     */
    val creation: CreationStatusSource = MutableCreationStatusSource(),
    /**
     * The rename-status read-model (capability `event-rename`), the create twin, with the same inert
     * default for the same reason.
     */
    val rename: RenameStatusSource = MutableRenameStatusSource(),
    /**
     * Download progress (capability `photo-download`).
     *
     * The default is a READ `(0, 0)`, spelled out rather than taken from the fake's own default, and the
     * distinction is the point: "this host has no download arm" is an ANSWER, while the fake's default
     * (`DownloadProgress.UNREAD`) means "nothing has been read", which holds the health at `Loading`
     * forever. A host that never wires downloads means the first; the store-backed source on device
     * means the second until its first refresh (capability `sync-status`).
     */
    val download: DownloadStatusSource = InMemoryDownloadStatusSource(DownloadProgress(0, 0)),
    /**
     * Attestation health (capability `device-attestation`): false only when this device's token is
     * UNUSABLE (absent, unreadable, or expired) and the refresh could not obtain one. Never false for a
     * token merely due for renewal — that one still authorizes every upload, and saying otherwise told a
     * member sharing was paused with six days of token left (`SNAPSYNC-20`). The feature that owns the
     * fact also owns the rule that a verdict never outlives the refresh that produced it, so nothing
     * downstream reasons about how old this value is. Defaults to always-true.
     */
    val attested: StateFlow<Boolean> = MutableStateFlow(true),
    /**
     * The in-progress join/switch confirmation (capability `join-event`). Event-driven rather than
     * level-triggered: the gate sets it on a decoded interactive event link and clears it on
     * commit/cancel. Injected — defaulting to a fresh instance — so the forge harness can forge any
     * `JoinPhase` by writing this cell directly; production and the full-stack harness accept the
     * default and let the gate drive it.
     */
    val pending: MutablePendingJoinSource = MutablePendingJoinSource(),
    /**
     * Whether the backend is refusing this build as too old, and the version it named (capability
     * `min-app-version`) — `AppVersionGate.refusal`, written by the shared HTTP client's interceptor.
     *
     * An OBSERVATION, like every field here, so it does not cross `flow/` (`module-architecture`,
     * "Commands cross one door": reads do not). Defaults to never-refused, so a host with no backend —
     * the forge, and every test that does not exercise it — constructs unchanged.
     */
    val versionRefusal: StateFlow<AppVersionGate.Refusal?> = MutableStateFlow(null),
    /**
     * This build's App Store page, or `null` when it carries none. A build constant supplied by the
     * composition root, not a source — it is here because the ONE screen that needs it is the refusal
     * above, and pairing them is what stops a host wiring the state without the remedy.
     */
    val appStoreUrl: String? = null,
)

/**
 * The two out-channels [StatusContainerHost] writes to, as distinct from everything it reads.
 *
 * They stay two fields rather than one because they carry different severities, and severity is
 * deliberately absent from this module's vocabulary: presentation names a need, and the composition
 * decides what level answers it.
 */
class StatusDiagnostics(
    /**
     * Dev-path abort logging: the headless negative oracle for a `SNAPSYNC_EVENT_LINK` run (autoJoin has
     * no UI to show a load/commit failure, and a gate parked on a failed details load has no one watching
     * its dialog). No-op by default; the iOS shell wires it into `debug.log`.
     */
    val log: (String) -> Unit = {},
    /**
     * The container's ERROR seam (spec `sync-status-screen`, "A failing command never disables the status
     * container"): every throwable that escapes an intent arrives here instead of propagating. The
     * composition binds it to `Error` severity, which is the threshold at which a Kermit line becomes a
     * crash-reporting EVENT rather than a breadcrumb (capability `crash-reporting`).
     *
     * No-op by default — but note that the DEFAULT still keeps the container alive, because it is the
     * handler's PRESENCE that stops Orbit's rethrow. A host binding nothing loses the report, never the
     * liveness.
     */
    val onIntentError: (Throwable) -> Unit = {},
)
