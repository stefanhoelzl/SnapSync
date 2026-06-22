package app.snapsync.status

import app.snapsync.engine.LedgerAggregates
import app.snapsync.engine.LedgerWatcher
import app.snapsync.gallery.GalleryStatusSource
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * The real [SyncStatusSource]: read-only ledger aggregates × permission × gallery size, minted into
 * snapshots. Unlike a synchronous fake, a SQLite-backed source cannot read its truth at
 * construction, so the factory does NOT suspend: it seeds [SyncStatus.Loading] and, on [scope],
 * collects the three sources combined, emitting [SyncStatus.Ready] once **all three** have produced
 * a first value (`combine` waits for every input) and on every change after. The aggregate reads run
 * on [dispatcher] (default [Dispatchers.Default]), so the backend's SQL never executes on whatever
 * dispatcher [scope] uses (e.g. the iOS main thread); tests inject a test dispatcher to keep
 * virtual-time control.
 *
 * `active = permission == GRANTED` is the shared operational-state rule — it lives here and only
 * here. `total` comes from the live gallery, not the ledger. The source never estimates
 * (`estimatedRemaining = null`) and never gives up (`failed = 0`, retry-forever).
 */
fun LedgerSyncStatusSource(
    watcher: LedgerWatcher,
    permission: PermissionStatusSource,
    gallery: GalleryStatusSource,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): SyncStatusSource {
    val status = MutableStateFlow<SyncStatus>(SyncStatus.Loading)
    scope.launch {
        combine(
            watcher.aggregates.flowOn(dispatcher),
            permission.permission,
            gallery.size,
            ::mint,
        ).collect { status.value = SyncStatus.Ready(it) }
    }
    return object : SyncStatusSource {
        override val status: StateFlow<SyncStatus> = status
    }
}

private fun mint(
    aggregates: LedgerAggregates,
    permission: PermissionStatus,
    total: Int,
) = SyncProgress(
    pending = aggregates.pending,
    completed = aggregates.completed,
    total = total,
    failed = 0,
    active = permission == PermissionStatus.GRANTED,
    estimatedRemaining = null,
    lastFinishedAt = aggregates.newestCompletionAt,
)
