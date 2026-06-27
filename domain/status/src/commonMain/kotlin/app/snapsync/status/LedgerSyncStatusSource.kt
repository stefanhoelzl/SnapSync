package app.snapsync.status

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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch

/**
 * The real [SyncStatusSource]: the read-only ledger snapshot, projected through the live
 * observed-completions overlay, × permission × gallery size, minted into snapshots. Unlike a
 * synchronous fake, a SQLite-backed source cannot read its truth at construction, so the factory does
 * NOT suspend: it seeds [SyncStatus.Loading] and, on [scope], collects the inputs combined, emitting
 * [SyncStatus.Ready] once the snapshot, permission, **and** gallery size have each produced a first
 * value (`combine` waits for every input) and on every change after. The snapshot read runs on
 * [dispatcher] (default [Dispatchers.Default]), so the backend's SQL never executes on whatever
 * dispatcher [scope] uses (e.g. the iOS main thread); tests inject a test dispatcher to keep
 * virtual-time control.
 *
 * The observed set seeds synchronously (an empty set is a valid first value), so it never delays the
 * first `Ready`; with an empty set the overlay is the identity and the counts equal the ledger
 * snapshot. `active = permission == GRANTED` is the shared operational-state rule — it lives here and
 * only here. `total` comes from the live gallery, not the ledger. The source never estimates
 * (`estimatedRemaining = null`) and never gives up (`failed = 0`, retry-forever).
 */
fun LedgerSyncStatusSource(
    watcher: LedgerWatcher,
    permission: PermissionStatusSource,
    gallery: GalleryStatusSource,
    observed: ObservedCompletionsSource,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): SyncStatusSource {
    val status = MutableStateFlow<SyncStatus>(SyncStatus.Loading)
    scope.launch {
        combine(overlaidCounts(watcher, observed, dispatcher), permission.permission, gallery.size, ::mint)
            .collect { status.value = SyncStatus.Ready(it) }
    }
    return object : SyncStatusSource {
        override val status: StateFlow<SyncStatus> = status
    }
}

// The ledger snapshot × the observed keys, carried through the sticky retention and the overlay. The
// scan carries the retained observed set across emissions; the initial accumulator has no counts yet,
// so it is dropped (mapNotNull) and the first real value is the first (snapshot, observed) pair.
private fun overlaidCounts(
    watcher: LedgerWatcher,
    observed: ObservedCompletionsSource,
    dispatcher: CoroutineDispatcher,
) = combine(watcher.snapshot.flowOn(dispatcher), observed.keys) { snapshot, keys -> snapshot to keys }
    .scan(StickyAccumulator(emptySet(), null)) { acc, (snapshot, fresh) ->
        val retained = stickyRetain(acc.retained, fresh, snapshot)
        StickyAccumulator(retained, overlay(snapshot, retained))
    }
    .mapNotNull { it.overlaid }
    .distinctUntilChanged()

private class StickyAccumulator(val retained: Set<String>, val overlaid: Overlaid?)

private fun mint(
    overlaid: Overlaid,
    permission: PermissionStatus,
    total: Int,
) = SyncProgress(
    pending = overlaid.pending,
    completed = overlaid.completed,
    total = total,
    failed = 0,
    active = permission == PermissionStatus.GRANTED,
    estimatedRemaining = null,
)
