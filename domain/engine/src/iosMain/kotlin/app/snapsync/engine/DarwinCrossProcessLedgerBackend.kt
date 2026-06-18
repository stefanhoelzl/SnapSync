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
 * notification (`notify(3)`, the iOS cross-process signal): every [put] posts it, and [changes]
 * merges an observer of it with the delegate's own in-process dings.
 *
 * The ding is a pure level trigger (no payload) exactly like the seam promises, so the
 * self-delivered echo a writer receives for its own post is harmless (the watcher just re-reads).
 * The observer is registered for process lifetime and never removed (v1 has no teardown).
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

    override suspend fun put(entry: LedgerEntry) {
        delegate.put(entry)
        notify_post(notificationName)
    }

    companion object {
        /** The Darwin notify name both processes agree on for "the ledger changed". */
        const val LEDGER_CHANGED_NOTIFICATION: String = "group.app.snapsync.ledger.changed"
    }
}
