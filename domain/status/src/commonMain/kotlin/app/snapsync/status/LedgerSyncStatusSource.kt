package app.snapsync.status

import app.snapsync.engine.LedgerAggregates
import app.snapsync.engine.LedgerWatcher
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The real [SyncStatusSource]: read-only ledger aggregates × permission, minted into snapshots.
 * The factory suspends for the watcher's current truth before constructing, so the seam's
 * synchronous-first-value promise holds. `active = permission == GRANTED` is the shared
 * operational-state rule — it lives here and only here. The v1 source never estimates
 * (`estimatedRemaining = null`) and never gives up (`failed = 0`, retry-forever).
 */
suspend fun LedgerSyncStatusSource(
    watcher: LedgerWatcher,
    permission: PermissionStatusSource,
    scope: CoroutineScope,
): SyncStatusSource {
    val status = MutableStateFlow(mint(watcher.aggregates.first(), permission.permission.value))
    scope.launch {
        combine(watcher.aggregates, permission.permission, ::mint).collect { status.value = it }
    }
    return object : SyncStatusSource {
        override val status: StateFlow<SyncStatus> = status
    }
}

private fun mint(aggregates: LedgerAggregates, permission: PermissionStatus) = SyncStatus(
    pending = aggregates.pending,
    completed = aggregates.completed,
    failed = 0,
    active = permission == PermissionStatus.GRANTED,
    estimatedRemaining = null,
    lastFinishedAt = aggregates.newestCompletionAt,
)
