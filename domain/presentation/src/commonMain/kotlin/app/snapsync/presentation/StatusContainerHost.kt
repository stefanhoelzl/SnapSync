package app.snapsync.presentation

import app.snapsync.config.ConfigDecodeResult
import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.config.EventConfigPayload
import app.snapsync.config.decodeConfigUrl
import app.snapsync.config.encodeConfigUrl
import app.snapsync.eventcreation.CreationFailureReason
import app.snapsync.eventcreation.CreationStatus
import app.snapsync.eventcreation.CreationStatusSource
import app.snapsync.eventcreation.EventCreator
import app.snapsync.eventcreation.MutableCreationStatusSource
import app.snapsync.eventcreation.NoOpEventCreator
import app.snapsync.eventstatus.EventStatus
import app.snapsync.eventstatus.EventStatusSource
import app.snapsync.eventstatus.MutableEventStatusSource
import app.snapsync.permission.PermissionRequester
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.status.ObservedCompletionsSource
import app.snapsync.status.NoObservedCompletions
import app.snapsync.status.SyncStatus
import app.snapsync.status.SyncState
import app.snapsync.status.SyncProgress
import app.snapsync.status.SyncStatusSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container

@OptIn(ExperimentalCoroutinesApi::class)
class StatusContainerHost(
    syncSource: SyncStatusSource,
    permissionSource: PermissionStatusSource,
    private val requester: PermissionRequester,
    configSource: ConfigSource,
    private val store: ConfigStore,
    scope: CoroutineScope,
    // The observed-completions overlay refresh and the platform foreground signal. Defaults make the
    // overlay inert (no-op source, always-foreground) so non-iOS hosts and tests construct unchanged;
    // iOS injects the real PhotoKit-backed source and a scenePhase-driven foreground signal.
    observed: ObservedCompletionsSource = NoObservedCompletions,
    foreground: Flow<Boolean> = flowOf(true),
    pollInterval: Duration = 10.seconds,
    // The re-join status seam. Defaults to an always-`Idle` source so non-iOS hosts and tests that
    // don't exercise the join construct unchanged (Idle falls through to the sync hero); iOS injects
    // the same instance the JoinEvent drives.
    eventStatusSource: EventStatusSource = MutableEventStatusSource(),
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
) : ContainerHost<UiState, SetupEffect> {

    override val container: Container<UiState, SetupEffect> =
        scope.container(
            // All three seams hold their current truth synchronously, so the first state the
            // screen can ever render derives from real values — never a guess or a
            // loading placeholder.
            reduceFrom(
                configSource.config.value,
                permissionSource.permission.value,
                eventStatusSource.status.value,
                syncSource.status.value,
                creationStatusSource.creationStatus.value,
            ),
        ) {
            intent {
                // Five sources combine into a holder (combine's max typed arity); each new value
                // reduces straight to a UI state. The screen reports no relative time, so there is
                // no clock and no periodic re-render — only a real source change re-emits.
                combine(
                    configSource.config,
                    permissionSource.permission,
                    eventStatusSource.status,
                    syncSource.status,
                    creationStatusSource.creationStatus,
                ) { config, permission, eventStatus, snapshot, creation ->
                    reduceFrom(config, permission, eventStatus, snapshot, creation)
                }
                    .collect { ui -> reduce { ui } }
            }
            intent {
                // Keep progress live while the screen is shown: refresh the observed-completions
                // overlay whenever the app is foreground AND work is still pending, on a bounded
                // interval (job success has no notification — polling is the only way to observe it
                // between the extension's coarse runs). Refreshing stops the moment pending hits zero
                // (the projection drained) or foreground is lost, and resumes immediately on return.
                combine(foreground, syncSource.status) { fg, snapshot ->
                    fg && snapshot is SyncStatus.Ready && snapshot.progress.pending > 0
                }
                    .distinctUntilChanged()
                    .flatMapLatest { active -> if (active) pollTicks(pollInterval) else emptyFlow() }
                    .collect { observed.refresh() }
            }
        }

    /**
     * The event's invite deeplink, derived from the persisted config (`eventId -> encodeConfigUrl`,
     * the inverse of the decode run on a scanned QR). One source feeding both the rendered QR and the
     * share action so the two can never drift; `null` whenever no event is configured. Deterministic —
     * the same URL a scanner of the event's QR would receive.
     */
    val inviteUrl: StateFlow<String?> =
        configSource.config
            .map { it?.let { payload -> encodeConfigUrl(payload) } }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                configSource.config.value?.let { encodeConfigUrl(it) },
            )

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
     * leave action, which disables the producer, resets the ledger, clears the discovery cursor and
     * the persisted config, and returns the event status to `Idle`. The config going `null` makes the
     * reduction fall back to the setup gate — no new `UiState` and no reduction branch here.
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
     * A deeplink arrived (forwarded raw from the platform). Decode it with the shared codec; a
     * valid config is persisted via the store (its change arrives back through ConfigSource), an
     * invalid one flashes the transient error without touching persisted state.
     */
    fun onOpenUrl(raw: String) = intent {
        when (val result = decodeConfigUrl(raw)) {
            is ConfigDecodeResult.Success -> store.save(result.payload)
            is ConfigDecodeResult.Failure -> postSideEffect(SetupEffect.InvalidConfigLink)
        }
    }
}

// Emits immediately (refresh on activation) then every [interval] until the collector is cancelled
// (which flatMapLatest does the moment foreground/pending goes false).
private fun pollTicks(interval: Duration): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(interval)
    }
}

// Create-layer precedence (config-presence only): without a connected event there is nothing to back
// up, so the create layer replaces the hero regardless of permission, join, or snapshot — the top
// rung. Once config is present, a permission that is not fully granted has no meaningful sync state to
// show, so it surfaces as PermissionBlocked (NOT_DETERMINED priming / DENIED settings path),
// outranking the join/sync chain.
private fun reduceFrom(
    config: EventConfigPayload?,
    permission: PermissionStatus,
    eventStatus: EventStatus,
    snapshot: SyncStatus,
    creation: CreationStatus,
): UiState {
    if (config == null) {
        return when (creation) {
            CreationStatus.InFlight -> UiState.CreatingEvent
            is CreationStatus.Failed -> UiState.CreateEvent(error = creation.reason.message())
            CreationStatus.Idle -> UiState.CreateEvent()
        }
    }
    if (permission != PermissionStatus.GRANTED) {
        return UiState.PermissionBlocked(permission)
    }
    // The join phase sits below the setup gate + permission and above the sync hero (setup-gate
    // precedence): once config + permission pass, a join in flight/failed outranks the snapshot;
    // Joined/Idle fall through to the hero.
    when (eventStatus) {
        EventStatus.Joining -> return UiState.Joining
        EventStatus.JoinFailed -> return UiState.JoinFailed
        EventStatus.Joined, EventStatus.Idle -> Unit
    }
    // Loading is reachable only here: an absent config short-circuits to the gate and a non-GRANTED
    // permission to PermissionBlocked, regardless of the snapshot, so "reading the ledger" is shown
    // only once config + permission both pass.
    return when (snapshot) {
        SyncStatus.Loading -> UiState.Loading
        is SyncStatus.Ready -> snapshot.progress.toUiState()
    }
}

private fun SyncProgress.toUiState(): UiState = when (state) {
    SyncState.IN_PROGRESS -> UiState.InProgress(
        synced = synced,
        total = total,
        // Ledger photos still uploading (asset-counted pending) — the second caption's count.
        inProgress = pending,
    )
    SyncState.NOTHING_TO_SYNC -> UiState.NothingToSync
    SyncState.COMPLETE -> UiState.Completed(total = total)
}

// The inline create-error copy, formatted in presentation (UiState carries final display strings).
private fun CreationFailureReason.message(): String = when (this) {
    CreationFailureReason.INVALID_NAME -> "That name isn't valid."
    CreationFailureReason.SERVER -> "Couldn't reach the server."
}
