package app.snapsync.presentation

import app.snapsync.config.ConfigDecodeResult
import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.config.EventConfigPayload
import app.snapsync.config.decodeConfigUrl
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
) : ContainerHost<UiState, SetupEffect> {

    override val container: Container<UiState, SetupEffect> =
        scope.container(
            // All three seams hold their current truth synchronously, so the first state the
            // screen can ever render derives from real values — never a guess or a
            // loading placeholder.
            reduceFrom(
                configSource.config.value,
                permissionSource.permission.value,
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
                    syncSource.status,
                    minuteTicker(),
                ) { config, permission, snapshot, _ -> Triple(config, permission, snapshot) }
                    .collect { (config, permission, snapshot) ->
                        reduce { reduceFrom(config, permission, snapshot, clock.now()) }
                    }
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

// Two-input setup precedence (setup-gate): without both a connected storage and a full permission
// grant there is no meaningful sync state to show — the setup gate replaces the hero regardless of
// the snapshot.
private fun reduceFrom(
    config: EventConfigPayload?,
    permission: PermissionStatus,
    snapshot: SyncStatus,
    now: Instant,
): UiState {
    val storageConnected = config != null
    if (!storageConnected || permission != PermissionStatus.GRANTED) {
        return UiState.Setup(storageConnected, permission)
    }
    // Loading is reachable only here: an absent config or non-GRANTED permission short-circuits to
    // the gate regardless of the snapshot, so "reading the ledger" is shown only once both pass.
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
