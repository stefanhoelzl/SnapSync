package app.snapsync.engine

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import platform.darwin.dispatch_get_main_queue
import platform.darwin.notify_post
import platform.darwin.notify_register_dispatch
import platform.posix.int32_tVar

/**
 * The cross-process face of the iOS ledger. SQLite shares the rows between the app and extension
 * processes, but the [SqlDelightLedgerBackend.changes] ding is in-process only — a `put` by the
 * extension never wakes a watcher in the app. This decorator closes that gap with a Darwin
 * notification (`notify(3)`, the iOS cross-process signal): [changes] merges an observer of it with
 * the delegate's own in-process dings.
 *
 * The cross-process notification is **coalesced to once per writer work cycle**, not posted per
 * write: the extension calls [postLedgerChangedNotification] once after its `process()` cycle (see
 * `UploadExtensionRoot`). Per-`put` posting would make the app re-read the (now heavier) snapshot for
 * every row; one ding per cycle bounds it to one re-read for the whole batch. The app's own writes
 * (a re-provision `clear`) need no cross-process post — the extension reads fresh each cycle, and the
 * app's own watcher is woken by the in-process delegate ding.
 *
 * The ding is a pure level trigger (no payload) exactly like the seam promises, so a missed or
 * self-delivered notification is harmless (the watcher just re-reads on its next trigger). The
 * observer is registered for process lifetime and never removed (v1 has no teardown).
 */
@OptIn(ExperimentalForeignApi::class)
class DarwinCrossProcessLedgerBackend(
    private val delegate: LedgerBackend,
    private val notificationName: String = LEDGER_CHANGED_NOTIFICATION,
) : LedgerBackend {

    private val crossProcessDings = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        memScoped {
            val token = alloc<int32_tVar>()
            notify_register_dispatch(notificationName, token.ptr, dispatch_get_main_queue()) {
                crossProcessDings.tryEmit(Unit)
            }
        }
    }

    override val changes: Flow<Unit> = merge(delegate.changes, crossProcessDings)

    override suspend fun get(key: String): LedgerEntry? = delegate.get(key)

    override suspend fun aggregates(): LedgerAggregates = delegate.aggregates()

    override suspend fun pendingResources(): List<PendingResource> = delegate.pendingResources()

    // Writes delegate straight through; they no longer post the cross-process notification. The
    // writer process posts it once per cycle via postLedgerChangedNotification(); in-process watchers
    // are still woken by the delegate's own per-put ding (merged into `changes`).
    override suspend fun put(entry: LedgerEntry) = delegate.put(entry)

    override suspend fun clear() = delegate.clear()

    override suspend fun resetTo(entries: List<LedgerEntry>) = delegate.resetTo(entries)

    override suspend fun deleteByAssetId(assetId: String) = delegate.deleteByAssetId(assetId)

    override suspend fun retainAssets(keep: Set<String>) = delegate.retainAssets(keep)

    companion object {
        /** The Darwin notify name both processes agree on for "the ledger changed". */
        const val LEDGER_CHANGED_NOTIFICATION: String = "group.app.snapsync.ledger.changed"
    }
}

/**
 * Post the cross-process "ledger changed" Darwin notification once. The extension calls this after
 * its `process()` cycle so the app re-reads the ledger once for the whole batch the cycle wrote
 * (the per-`put` post was removed — see [DarwinCrossProcessLedgerBackend]).
 */
@OptIn(ExperimentalForeignApi::class)
fun postLedgerChangedNotification(
    notificationName: String = DarwinCrossProcessLedgerBackend.LEDGER_CHANGED_NOTIFICATION,
) {
    notify_post(notificationName)
}
