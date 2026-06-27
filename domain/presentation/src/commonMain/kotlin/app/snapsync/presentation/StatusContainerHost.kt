package app.snapsync.presentation

import app.snapsync.config.ConfigDecodeResult
import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.config.EventConfigPayload
import app.snapsync.config.decodeConfigUrl
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
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    private val clock: Clock = Clock.System,
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
                clock.now(),
            ),
        ) {
            intent {
                // The tick only re-renders the past (relative time). Estimates come from the
                // snapshot verbatim and are never aged here — if one should change, that's the
                // source's job via a new snapshot. Equal reductions are conflated by the
                // container's StateFlow, so a tick re-emits only when visible text changed.
                combine(
                    configSource.config,
                    permissionSource.permission,
                    eventStatusSource.status,
                    syncSource.status,
                    minuteTicker(),
                ) { config, permission, eventStatus, snapshot, _ ->
                    reduceFrom(config, permission, eventStatus, snapshot, clock.now())
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

    fun onRequestPermission() = intent { requester.request() }

    fun onOpenSettings() = intent { requester.openSettings() }

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

private fun minuteTicker(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(1.minutes)
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

// Setup-gate precedence (config-presence only): the gate stands between the user and the rest of the
// screen solely on config — without a connected event there is nothing to back up, so the gate
// replaces the hero regardless of permission or snapshot. Once config is present, a permission that
// is not fully granted has no meaningful sync state to show, so it surfaces on the status screen as
// PermissionBlocked (NOT_DETERMINED priming / DENIED settings path), outranking the join/sync chain.
private fun reduceFrom(
    config: EventConfigPayload?,
    permission: PermissionStatus,
    eventStatus: EventStatus,
    snapshot: SyncStatus,
    now: Instant,
): UiState {
    val storageConnected = config != null
    if (!storageConnected) {
        return UiState.Setup(storageConnected, permission)
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
        is SyncStatus.Ready -> snapshot.progress.toUiState(now)
    }
}

private fun SyncProgress.toUiState(now: Instant): UiState = when (state) {
    SyncState.IN_PROGRESS -> UiState.InProgress(
        synced = synced,
        total = total,
        // Ledger photos still uploading (asset-counted pending) — the second caption's count.
        inProgress = pending,
        // The last completion's age (null before anything completes — a bare "0 of N").
        finishedAgo = lastFinishedAt?.let { relativeTime(now - it) },
    )
    SyncState.NOTHING_TO_SYNC -> UiState.NothingToSync
    SyncState.COMPLETE -> UiState.Completed(total = total, finishedAgo = finishedAgo(now))
}

// COMPLETE implies a completed photo, so the ledger always has a completion timestamp; the
// null-guard keeps a forged/inconsistent snapshot from crashing the screen.
private fun SyncProgress.finishedAgo(now: Instant): String =
    lastFinishedAt?.let { relativeTime(now - it) } ?: "just now"

private fun relativeTime(elapsed: Duration): String = when {
    elapsed < 1.minutes -> "just now"
    elapsed < 1.hours -> "${elapsed.inWholeMinutes} min ago"
    elapsed < 1.days -> "${elapsed.inWholeHours} h ago"
    else -> "${elapsed.inWholeDays} d ago"
}
