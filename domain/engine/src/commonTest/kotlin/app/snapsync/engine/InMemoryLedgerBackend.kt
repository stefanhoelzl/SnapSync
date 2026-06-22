package app.snapsync.engine

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Test seam double: the dumbest possible row store. Mirrors the backend contract exactly —
 * verbatim storage, last write wins, no interpretation, a ding after every put.
 */
class InMemoryLedgerBackend : LedgerBackend {

    private val rows = mutableMapOf<String, LedgerEntry>()

    private val dings = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<Unit> = dings

    override suspend fun get(key: String): LedgerEntry? = rows[key]

    override suspend fun put(entry: LedgerEntry) {
        rows[entry.key] = entry
        dings.tryEmit(Unit)
    }

    override suspend fun clear() {
        rows.clear()
        dings.tryEmit(Unit)
    }

    override suspend fun deleteByKeyPrefix(prefix: String) {
        rows.keys.retainAll { !it.startsWith(prefix) }
        dings.tryEmit(Unit)
    }

    override suspend fun retainKeys(keep: Set<String>) {
        rows.keys.retainAll(keep)
        dings.tryEmit(Unit)
    }

    override suspend fun aggregates(): LedgerAggregates {
        val completed = rows.values.filter { it.state == LedgerState.COMPLETED }
        return LedgerAggregates(
            pending = rows.size - completed.size,
            completed = completed.size,
            newestCompletionAt = completed.maxOfOrNull { it.updatedAt },
        )
    }
}
