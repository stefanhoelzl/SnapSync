package app.snapsync.presentation

import app.snapsync.permission.PermissionRequester
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.sync.SyncState
import app.snapsync.sync.SyncStatus
import app.snapsync.sync.SyncStatusSource
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
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : ContainerHost<UiState, Nothing> {

    override val container: Container<UiState, Nothing> =
        scope.container(
            // Both seams hold their current truth synchronously, so the first state the
            // screen can ever render derives from real values — never a guess or a
            // loading placeholder.
            reduceFrom(permissionSource.permission.value, syncSource.status.value, clock.now()),
        ) {
            intent {
                // The tick only re-renders the past (relative time). Estimates come from the
                // snapshot verbatim and are never aged here — if one should change, that's the
                // source's job via a new snapshot. Equal reductions are conflated by the
                // container's StateFlow, so a tick re-emits only when visible text changed.
                combine(
                    permissionSource.permission,
                    syncSource.status,
                    minuteTicker(),
                ) { permission, snapshot, _ -> permission to snapshot }
                    .collect { (permission, snapshot) ->
                        reduce { reduceFrom(permission, snapshot, clock.now()) }
                    }
            }
        }

    fun onRequestPermission() = intent { requester.request() }

    fun onOpenSettings() = intent { requester.openSettings() }
}

private fun minuteTicker(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(1.minutes)
    }
}

// Permission-first precedence: without a full grant there is no meaningful sync state to
// show — the gate replaces the hero regardless of the snapshot.
private fun reduceFrom(permission: PermissionStatus, snapshot: SyncStatus, now: Instant): UiState =
    when (permission) {
        PermissionStatus.NOT_DETERMINED -> UiState.PermissionAsk
        PermissionStatus.DENIED -> UiState.PermissionDenied
        PermissionStatus.GRANTED -> snapshot.toUiState(now)
    }

private fun SyncStatus.toUiState(now: Instant): UiState = when (state) {
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
    SyncState.FAILED -> UiState.Failed(finishedAgo(now))
}

// Finished outcomes guarantee lastFinishedAt != null (classification branch order).
private fun SyncStatus.finishedAgo(now: Instant): String = relativeTime(now - lastFinishedAt!!)

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
