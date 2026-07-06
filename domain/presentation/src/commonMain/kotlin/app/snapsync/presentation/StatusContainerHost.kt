package app.snapsync.presentation

import app.snapsync.config.ConfigDecodeResult
import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.config.EventConfig
import app.snapsync.config.EventLinkPayload
import app.snapsync.config.decodeConfigUrl
import app.snapsync.config.encodeConfigUrl
import app.snapsync.eventcreation.CreationFailureReason
import app.snapsync.eventcreation.CreationStatus
import app.snapsync.eventcreation.CreationStatusSource
import app.snapsync.eventcreation.EventCreator
import app.snapsync.eventcreation.MutableCreationStatusSource
import app.snapsync.eventcreation.NoOpEventCreator
import app.snapsync.permission.PermissionRequester
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.status.DownloadProgress
import app.snapsync.status.DownloadStatusSource
import app.snapsync.status.InMemoryDownloadStatusSource
import app.snapsync.status.SyncStatus
import app.snapsync.status.SyncProgress
import app.snapsync.status.SyncStatusSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container

/**
 * The outcome of fetching an event's details for the join gate — the presentation-local mirror of
 * `join-event`'s `EventDetails`, kept here so this module gains no `:capability:join` dependency (the
 * app adapts one to the other). The gate MUST tell a **missing** event (block) from a **transient**
 * failure (retry).
 */
sealed interface JoinLoad {
    /** [createdAt] (the event's UTC `…Z` creation timestamp) seeds the join screen's cutoff default. */
    data class Found(val name: String?, val createdAt: String?) : JoinLoad
    data object NotFound : JoinLoad
    data object Failed : JoinLoad
}

class StatusContainerHost(
    syncSource: SyncStatusSource,
    permissionSource: PermissionStatusSource,
    private val requester: PermissionRequester,
    private val configSource: ConfigSource,
    private val store: ConfigStore,
    private val scope: CoroutineScope,
    // The create-event seams. Defaults make the create layer inert (always-Idle source, no-op
    // creator) so non-iOS hosts and tests that don't exercise create construct unchanged; iOS injects
    // the same instance the create use-case drives, and the real `EventCreator`.
    creationStatusSource: CreationStatusSource = MutableCreationStatusSource(),
    private val creator: EventCreator = NoOpEventCreator,
    // The leave action, injected as a plain suspend lambda (not the `LeaveEvent` type) so this
    // Compose-free module gains no engine/gallery dependency. Defaults to a no-op so non-iOS hosts
    // and tests construct unchanged and a confirmed leave there is inert; iOS binds it to
    // `LeaveEvent.leave`.
    private val leave: suspend () -> Unit = {},
    // The share action, injected as a plain `(String) -> Unit` lambda (not a named seam type) — the
    // same shape as `leave`. Defaults to a no-op so non-iOS hosts and tests construct unchanged and a
    // share there is inert; iOS binds it to a `UIActivityViewController` presentation.
    private val share: (String) -> Unit = {},
    // The join gate hooks (capability `join-event`), injected as plain lambdas (like `leave`) so this
    // module gains no `:capability:join` dependency. `loadJoinDetails` = `GET /events/:id` mapped to a
    // block/retry/ready outcome; `commitJoin` = enroll (register-only empty manifest) then provision,
    // returning `true` when joined (incl. the already-joined no-op) and `false` on a failed enrollment.
    // Defaults are inert (load fails, commit does nothing) so non-iOS hosts and tests that don't
    // exercise join construct unchanged; iOS binds them to the `JoinEvent` use-case.
    private val loadJoinDetails: suspend (eventId: String) -> JoinLoad = { JoinLoad.Failed },
    // `commitJoin` = enroll (register-only empty manifest) then provision with the chosen capture-date
    // cutoff (capability `photo-date-cutoff`; `null` = whole-library), returning `true` when joined.
    private val commitJoin: suspend (eventId: String, name: String?, minPhotoDate: String?) -> Boolean =
        { _, _, _ -> false },
    // Dev-path abort logging (autoJoin has no UI to show a load/commit failure). No-op by default.
    private val log: (String) -> Unit = {},
    // Download progress for the joined-layer "downloaded X of Y" line (capability `photo-download`).
    // Exposed as a screen-level StateFlow (like `inviteUrl`), NOT folded into `UiState` — it's an
    // independent indicator that doesn't gate upload classification. Defaults to inert (always 0 of 0)
    // so non-iOS hosts/tests construct unchanged; iOS injects the store-backed source.
    downloadSource: DownloadStatusSource = InMemoryDownloadStatusSource(),
) : ContainerHost<UiState, SetupEffect> {

    // Event-driven overlay for an in-progress join/switch confirmation (capability `join-event`). Not
    // derived from the level-triggered sources — the gate sets it on a decoded interactive deeplink and
    // clears it on commit/cancel. Folded into the reduction as a sixth flow.
    private val pending = MutableStateFlow<PendingJoin?>(null)

    override val container: Container<UiState, SetupEffect> =
        scope.container(
            // All seams hold their current truth synchronously, so the first state the screen can ever
            // render derives from real values — never a guess or a placeholder.
            reduceFrom(
                configSource.config.value,
                permissionSource.permission.value,
                syncSource.status.value,
                creationStatusSource.creationStatus.value,
                downloadSource.progress.value,
                pending.value,
            ),
        ) {
            intent {
                // The sources combine into a holder; each new value reduces straight to a UI state.
                // The screen reports no relative time, so there is no clock and no periodic re-render —
                // only a real source change (or a pending-join transition) re-emits.
                combine(
                    configSource.config,
                    permissionSource.permission,
                    syncSource.status,
                    creationStatusSource.creationStatus,
                    downloadSource.progress,
                    pending,
                ) { values ->
                    @Suppress("UNCHECKED_CAST")
                    reduceFrom(
                        values[0] as EventConfig?,
                        values[1] as PermissionStatus,
                        values[2] as SyncStatus,
                        values[3] as CreationStatus,
                        values[4] as DownloadProgress,
                        values[5] as PendingJoin?,
                    )
                }
                    .collect { ui -> reduce { ui } }
            }
        }

    /**
     * The event's invite deeplink, derived from the persisted config's `eventId` via
     * `encodeConfigUrl(EventLinkPayload(eventId))` — the inverse of the decode run on a scanned QR.
     * One source feeding both the rendered QR and the share action so the two can never drift; `null`
     * whenever no event is configured. Deterministic — the same URL a scanner of the event's QR would
     * receive.
     */
    val inviteUrl: StateFlow<String?> =
        configSource.config
            .map { it?.inviteUrl() }
            .stateIn(scope, SharingStarted.Eagerly, configSource.config.value?.inviteUrl())

    /**
     * The joined event's human-readable name for the screen title (fetched by id after joining, so it
     * may be `null` until a foreground refresh fills it). A screen-level param like [inviteUrl] — it
     * does not enter `UiState`, so the reduction gains no branch for it.
     */
    val eventName: StateFlow<String?> =
        configSource.config
            .map { it?.name }
            .stateIn(scope, SharingStarted.Eagerly, configSource.config.value?.name)

    /**
     * Create a new event with [name] (event-creation-ui). Delegates to the injected [EventCreator]
     * (fire-and-forget): it mints the event and, on success, provisions it through the same path a
     * scanned QR uses (config goes present, the reduction leaves the create layer). Permission is not
     * consulted here — a missing grant surfaces afterward via `PermissionBlocked`. The in-flight and
     * failure outcomes arrive back through `CreationStatusSource`; nothing is reduced here.
     */
    fun onCreateEvent(name: String) = intent { creator.create(name) }

    fun onRequestPermission() = intent { requester.request() }

    fun onOpenSettings() = intent { requester.openSettings() }

    /**
     * Leave the configured event (confirmed in the UI before this fires). Delegates to the injected
     * leave action, which disables the producer and clears the persisted config (the extension resets
     * its own private ledger on its next cycle). The config going `null` makes the reduction fall back
     * to the setup gate — no new `UiState` and no reduction branch here.
     */
    fun onLeaveEvent() = intent { leave() }

    /**
     * Share the event's invite deeplink (the joined-layer share action). Hands the current invite URL
     * to the injected platform share; fire-and-forget — no result is observed, and `UiState` is
     * unaffected (the system share UI is presented over the screen, not part of it). Inert when no
     * event is configured (no URL) or no real share is bound (the no-op default).
     */
    fun onShareInvite() = intent { inviteUrl.value?.let { share(it) } }

    /**
     * A deeplink arrived (forwarded raw from the platform). Decode it with the shared codec; an
     * invalid link flashes the transient error without touching state. A valid link opens the **join
     * gate** (capability `join-event`): `autoJoin` auto-confirms headlessly, otherwise a first join
     * opens the full-screen confirmation and a different event while joined opens a switch
     * confirmation. Re-scanning the already-joined event is a no-op (never re-enrolls).
     */
    fun onOpenUrl(raw: String) = intent {
        when (val result = decodeConfigUrl(raw)) {
            is ConfigDecodeResult.Failure -> postSideEffect(SetupEffect.InvalidConfigLink)
            is ConfigDecodeResult.Success -> {
                val eventId = result.payload.eventId
                val current = configSource.config.value
                when {
                    result.payload.autoJoin -> autoConfirm(eventId, result.payload.minPhotoDate)
                    current == null -> startPending(eventId)               // first join → JoiningEvent
                    current.eventId != eventId -> startPending(eventId)    // switch → Joined.pendingSwitch
                    else -> Unit                                           // same event → no-op
                }
            }
        }
    }

    /** Retry the details fetch after a transient load failure. */
    fun onRetryLoad() = intent {
        val p = pending.value ?: return@intent
        pending.value = p.copy(phase = JoinPhase.Loading)
        loadInto(p.eventId)
    }

    /** Confirm a first join with the chosen capture-date [cutoff]: enroll → provision (no leave). */
    fun onConfirmJoin(cutoff: String?) = intent { commit(withLeave = false, cutoff = cutoff) }

    /** Confirm a switch: leave the current event, then enroll → provision the new one with [cutoff]. */
    fun onConfirmSwitch(cutoff: String?) = intent { commit(withLeave = true, cutoff = cutoff) }

    /** Retry a failed commit — the leave (if any) already succeeded, so this re-runs only the join. */
    fun onRetryJoin(cutoff: String?) = intent { commit(withLeave = false, cutoff = cutoff) }

    /** Discard the pending join/switch, returning to the base screen. */
    fun onCancelJoin() = intent { pending.value = null }

    /** Discard the pending switch, staying in the current event. */
    fun onCancelSwitch() = intent { pending.value = null }

    /**
     * A create just minted [eventId] (capability `event-creation-ui`): route it into the **same**
     * pending-join gate a scanned QR uses — non-auto-confirmed — so the creator loads the event, picks a
     * capture-date cutoff, and confirms like any joiner. The `POST /events` already minted the event, so
     * the gate holds a real `eventId` and performs a real details load; provision happens on confirm.
     */
    fun onEventCreated(eventId: String) = intent { startPending(eventId) }

    // The gate's async work runs INLINE within the orbit intent (not on a side scope) so each pending
    // transition reduces through the container's own pipeline deterministically. A modal join is fine
    // to serialize; a real fetch suspends here, yielding a Loading frame before the result.
    private suspend fun startPending(eventId: String) {
        pending.value = PendingJoin(eventId, JoinPhase.Loading)
        loadInto(eventId)
    }

    private suspend fun loadInto(eventId: String) {
        val phase = when (val load = loadJoinDetails(eventId)) {
            is JoinLoad.Found -> JoinPhase.Ready(load.name, load.createdAt)
            JoinLoad.NotFound -> JoinPhase.NotFound
            JoinLoad.Failed -> JoinPhase.LoadFailed
        }
        // Only apply if this fetch is still the active pending target (not cancelled/superseded).
        pending.value?.let { if (it.eventId == eventId) pending.value = it.copy(phase = phase) }
    }

    private suspend fun commit(withLeave: Boolean, cutoff: String?) {
        val p = pending.value ?: return
        // Only a loaded (Ready) or previously-failed (CommitFailed) surface can be confirmed; a
        // still-loading/blocked/committing phase ignores the action.
        if (p.phase !is JoinPhase.Ready && p.phase !is JoinPhase.CommitFailed) return
        val name = p.phase.name()
        pending.value = p.copy(phase = JoinPhase.Committing(name))
        if (withLeave) leave()
        if (commitJoin(p.eventId, name, cutoff)) {
            // Success: config flips present via ConfigSource → reduces to Joined; drop the overlay.
            if (pending.value?.eventId == p.eventId) pending.value = null
        } else if (pending.value?.eventId == p.eventId) {
            pending.value = p.copy(phase = JoinPhase.CommitFailed(name))
        }
    }

    /**
     * The dev/headless auto-confirm path (`autoJoin=true`): run the same gate — fetch details, leave a
     * different current event first — but auto-fire the confirm on a successful load. No UI, so a load
     * or commit failure aborts and logs rather than parking on a retryable state.
     */
    private suspend fun autoConfirm(eventId: String, explicitCutoff: String?) {
        val load = loadJoinDetails(eventId)
        if (load !is JoinLoad.Found) {
            log("autoJoin aborted: details load did not succeed for $eventId ($load)")
            return
        }
        val current = configSource.config.value
        if (current != null && current.eventId != eventId) leave()
        // The auto-fired confirm uses the default cutoff (the loaded `createdAt`), unless the deeplink
        // supplied an explicit dev/test cutoff (capability `photo-date-cutoff`).
        val cutoff = explicitCutoff ?: load.createdAt
        if (!commitJoin(eventId, load.name, cutoff)) log("autoJoin aborted: enrollment failed for $eventId")
    }
}

/** The pending join's target and phase; the reducer maps it to `JoiningEvent` or `Joined.pendingSwitch`. */
private data class PendingJoin(val eventId: String, val phase: JoinPhase)

/** The loaded/committing name carried by a phase, if any (for re-issuing the commit on confirm/retry). */
private fun JoinPhase.name(): String? = when (this) {
    is JoinPhase.Ready -> name
    is JoinPhase.Committing -> name
    is JoinPhase.CommitFailed -> name
    JoinPhase.Loading, JoinPhase.NotFound, JoinPhase.LoadFailed -> null
}

// Config presence is the top rung: without a connected event there is nothing to share, so the create
// layer replaces everything regardless of permission or snapshot. Once config is present the screen is
// ALWAYS the joined layer (name · QR · share · leave) — permission and sync activity are moods of the
// one-line status, never a hero-replacing gate. There is no join-status rung: reconciliation runs in
// the extension and status is read from the completeness listing.
private fun reduceFrom(
    config: EventConfig?,
    permission: PermissionStatus,
    snapshot: SyncStatus,
    creation: CreationStatus,
    download: DownloadProgress,
    pending: PendingJoin?,
): UiState {
    if (config == null) {
        // A pending interactive join outranks the create layer (a switch whose leave already ran also
        // lands here — a transient no-event, shown full-screen with a Retry).
        if (pending != null) return UiState.JoiningEvent(pending.eventId, pending.phase)
        return when (creation) {
            CreationStatus.InFlight -> UiState.CreatingEvent
            is CreationStatus.Failed -> UiState.CreateEvent(error = creation.reason.message())
            CreationStatus.Idle -> UiState.CreateEvent()
        }
    }
    val health = when {
        // Missing permission is the sole attention state — the only reason contribution cannot run.
        permission != PermissionStatus.GRANTED -> SyncHealth.NeedsAccess(permission)
        // Joined but persisted state not read yet — a neutral first frame (the joined chrome still shows).
        snapshot is SyncStatus.Loading -> SyncHealth.Loading
        snapshot is SyncStatus.Ready -> syncHealth(snapshot.progress, download)
        else -> SyncHealth.Loading
    }
    // A pending join for a DIFFERENT event while joined is a switch confirmation over the joined screen.
    val pendingSwitch = pending?.let { PendingSwitch(it.eventId, it.phase) }
    return UiState.Joined(health, pendingSwitch)
}

// Shown tracks completeness (never lies about "everything up/received"); pulse tracks live activity
// (never fakes motion). In sync exactly when both directions are settled.
private fun syncHealth(progress: SyncProgress, download: DownloadProgress): SyncHealth {
    val upload = arrowOf(shown = progress.synced < progress.total, pulsing = progress.pending > 0)
    val downloadArrow = arrowOf(shown = download.downloaded < download.total, pulsing = download.inFlight > 0)
    return if (upload == Arrow.HIDDEN && downloadArrow == Arrow.HIDDEN) {
        SyncHealth.InSync
    } else {
        SyncHealth.Syncing(upload = upload, download = downloadArrow)
    }
}

private fun arrowOf(shown: Boolean, pulsing: Boolean): Arrow =
    if (!shown) Arrow.HIDDEN else if (pulsing) Arrow.PULSING else Arrow.STATIC

// Derive the invite deeplink from the persisted config's eventId (the wire payload is eventId-only).
private fun EventConfig.inviteUrl(): String = encodeConfigUrl(EventLinkPayload(eventId))

// The inline create-error copy, formatted in presentation (UiState carries final display strings).
private fun CreationFailureReason.message(): String = when (this) {
    CreationFailureReason.INVALID_NAME -> "That name isn't valid."
    CreationFailureReason.SERVER -> "Couldn't reach the server."
}
