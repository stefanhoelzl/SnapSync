package app.snapsync.presentation

import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.feature.download.readmodel.DownloadProgress
import app.snapsync.feature.membership.readmodel.RenameStatus
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.StoreLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.snapsync.model.VersionRefusal

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
 * `docs/architecture.md`, "Commands cross one door"); the armed presentation gate enforces it, and the
 * shell or the harness passes each flow in. The defaults are all **inert** so a host that does not
 * exercise an arm — the forge reviewing a forged state, a test that only drives the join gate —
 * constructs without naming it.
 */
class StatusSources(
    val sync: SyncStatusSource,
    /**
     * The photo-permission read-model. Observed for the health rung, and for whether confirming a join
     * also raises iOS's photo-access dialog (capability `join-event`).
     */
    val permission: StateFlow<GalleryAccess>,
    /** The persisted membership. Config presence is the reduction's top rung. */
    val config: StateFlow<EventConfig?>,
    /**
     * The create-status read-model. Inert by default (always `Idle`) so a host that never creates
     * constructs unchanged; the iOS shell injects the instance the create use-case drives.
     */
    val creation: StateFlow<CreationStatus> = MutableStateFlow(CreationStatus.Idle),
    /**
     * The rename-status read-model (capability `manage-membership`), the create twin, with the same inert
     * default for the same reason.
     */
    val rename: StateFlow<RenameStatus> = MutableStateFlow(RenameStatus.Idle),
    /**
     * Download progress (capability `receiving-photos`).
     *
     * The default is a READ `(0, 0)`, and the distinction is the point: "this host has no download arm" is an
     * ANSWER, while `DownloadProgress.UNREAD` means "nothing has been read", which holds the health at `Loading`
     * forever. A host that never wires downloads means the first; the store-backed source on device means the
     * second until its first refresh (capability `sync-status`).
     */
    val download: StateFlow<DownloadProgress> = MutableStateFlow(DownloadProgress(0, 0)),
    /**
     * Attestation health (capability `privacy-security`): false only when this device's token is
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
     * commit/cancel. Injected — defaulting to a fresh instance, which the gate drives.
     */
    val pending: MutableStateFlow<PendingJoin?> = MutableStateFlow(null),
    /**
     * Whether the backend is refusing this build as too old, and the version it named (capability
     * `app-update-required`) — `AppVersionGate.refusal`, written by the core's authenticated backend on every backend answer.
     *
     * An OBSERVATION, like every field here, so it does not cross `flow/` (`docs/architecture.md`,
     * "Commands cross one door": reads do not). Defaults to never-refused, so a host with no backend —
     * every test that does not exercise it — constructs unchanged.
     */
    val versionRefusal: StateFlow<VersionRefusal?> = MutableStateFlow(null),
    /**
     * This build's store page, or `null` when it carries none. A build constant supplied by the
     * composition root, not a source — it is here because the ONE screen that needs it is the refusal
     * above, and pairing them is what stops a host wiring the state without the remedy.
     */
    val store: StoreLink? = null,
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
     * Dev-path abort logging: the headless negative oracle for an `autoJoin` run (it has
     * no UI to show a load/commit failure, and a gate parked on a failed details load has no one watching
     * its dialog). The iOS shell wires it into `debug.log`.
     */
    val log: (String) -> Unit,
    /**
     * The container's ERROR seam (spec `sync-status`, "A failing command never disables the status
     * container"): every throwable that escapes an intent arrives here instead of propagating. The
     * composition binds it to `Error` severity, which is the threshold at which a Kermit line becomes a
     * crash-reporting EVENT rather than a breadcrumb (capability `privacy-security`).
     *
     * Required: a host that binds nothing here must say so. The container stays alive either way, because
     * it is the handler's PRESENCE that stops Orbit's rethrow — a host binding a no-op loses the report,
     * never the liveness.
     */
    val onIntentError: (Throwable) -> Unit,
)
