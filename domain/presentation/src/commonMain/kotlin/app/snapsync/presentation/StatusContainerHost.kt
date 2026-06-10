package app.snapsync.presentation

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
    source: SyncStatusSource,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : ContainerHost<UiState, Nothing> {

    override val container: Container<UiState, Nothing> =
        scope.container(UiState.NeverSynced) {
            intent {
                // The tick only re-renders the past (relative time). Estimates come from the
                // snapshot verbatim and are never aged here — if one should change, that's the
                // source's job via a new snapshot. Equal reductions are conflated by the
                // container's StateFlow, so a tick re-emits only when visible text changed.
                combine(source.status, minuteTicker()) { snapshot, _ -> snapshot }
                    .collect { snapshot -> reduce { snapshot.toUiState(clock.now()) } }
            }
        }
}

private fun minuteTicker(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(1.minutes)
    }
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
