package app.snapsync.presentation

import app.snapsync.config.ConfigDecodeResult
import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.config.S3ConfigPayload
import app.snapsync.config.decodeConfigUrl
import app.snapsync.permission.PermissionRequester
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.status.SyncStatus
import app.snapsync.status.SyncState
import app.snapsync.status.SyncProgress
import app.snapsync.status.SyncStatusSource
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container

class StatusContainerHost(
    syncSource: SyncStatusSource,
    permissionSource: PermissionStatusSource,
    private val requester: PermissionRequester,
    configSource: ConfigSource,
    private val store: ConfigStore,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
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

// Two-input setup precedence (setup-gate): without both a connected storage and a full permission
// grant there is no meaningful sync state to show — the setup gate replaces the hero regardless of
// the snapshot.
private fun reduceFrom(
    config: S3ConfigPayload?,
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
    SyncState.NEVER_SYNCED -> UiState.NeverSynced
    SyncState.IN_PROGRESS -> UiState.InProgress(
        // Processed-of-total: the indicator always reaches the end of a pass; the outcome
        // headline delivers the verdict.
        fraction = (completed + failed).toFloat() / (pending + completed + failed),
        estimate = estimateText(estimatedRemaining),
    )
    SyncState.SUSPENDED -> UiState.Suspended
    SyncState.COMPLETE -> UiState.Complete(finishedAgo(now))
    SyncState.INCOMPLETE -> UiState.Incomplete(finishedAgo(now))
}

// Finished outcomes guarantee lastFinishedAt != null (classification branch order).
private fun SyncProgress.finishedAgo(now: Instant): String = relativeTime(now - lastFinishedAt!!)

private fun relativeTime(elapsed: Duration): String = when {
    elapsed < 1.minutes -> "just now"
    elapsed < 1.hours -> "${elapsed.inWholeMinutes} min ago"
    elapsed < 1.days -> "${elapsed.inWholeHours} h ago"
    else -> "${elapsed.inWholeDays} d ago"
}

private fun estimateText(remaining: Duration?): String = when {
    remaining == null -> "estimating…"
    remaining < 1.minutes -> "less than a minute left"
    remaining < 1.hours -> "~${remaining.inWholeMinutes} min left"
    else -> "~${remaining.inWholeHours} h left"
}
