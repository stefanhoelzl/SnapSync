package app.snapsync.ios.upload

import app.snapsync.engine.LedgerAggregates
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerEntry
import app.snapsync.engine.LedgerState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** A minimal in-memory [LedgerBackend] test double — a last-write-wins map plus a put ding. */
class InMemoryLedgerBackend : LedgerBackend {

    private val entries = mutableMapOf<String, LedgerEntry>()
    private val dings = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<Unit> = dings

    override suspend fun get(key: String): LedgerEntry? = entries[key]

    override suspend fun put(entry: LedgerEntry) {
        entries[entry.key] = entry
        dings.tryEmit(Unit)
    }

    override suspend fun aggregates(): LedgerAggregates {
        val completed = entries.values.filter { it.state == LedgerState.COMPLETED }
        return LedgerAggregates(
            pending = entries.values.count { it.state != LedgerState.COMPLETED },
            completed = completed.size,
            newestCompletionAt = completed.maxByOrNull { it.updatedAt }?.updatedAt,
        )
    }
}
