package app.snapsync.engine

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.snapsync.engine.db.LedgerDatabase
import app.snapsync.engine.db.LedgerRow
import kotlin.time.Instant
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * [LedgerBackend] over the SQLDelight [LedgerDatabase] (schema: `Ledger.sq` — one table, key
 * primary key, writer-stamped `updatedAt`, no further indexes). [put] is the single
 * `INSERT OR REPLACE` statement, atomic on its own; [aggregates] is one SQL round-trip, so its
 * counts are mutually consistent. The driver decides where the database lives (JVM sqlite for
 * tests today; native driver with an App-Group path is the iOS slice's).
 */
class SqlDelightLedgerBackend(database: LedgerDatabase) : LedgerBackend {

    private val queries = database.ledgerQueries

    private val dings = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<Unit> = dings

    override suspend fun get(key: String): LedgerEntry? =
        queries.get(key) { _, assetId, state, attempt, updatedAt ->
            LedgerEntry(key, assetId, state, attempt.toInt(), updatedAt)
        }.executeAsOneOrNull()

    override suspend fun put(entry: LedgerEntry) {
        queries.put(entry.key, entry.assetId, entry.state, entry.attempt.toLong(), entry.updatedAt)
        dings.tryEmit(Unit)
    }

    override suspend fun aggregates(): LedgerAggregates =
        queries.aggregates { pending, completed, newestCompletionAt ->
            LedgerAggregates(pending.toInt(), completed.toInt(), newestCompletionAt)
        }.executeAsOne()

    override suspend fun pendingResources(): List<PendingResource> =
        queries.selectPending { assetId, key -> PendingResource(assetId, key) }.executeAsList()

    override suspend fun clear() {
        queries.deleteAll()
        dings.tryEmit(Unit)
    }

    override suspend fun resetTo(entries: List<LedgerEntry>) {
        // One transaction: delete-all then insert each. If any statement throws, SQLDelight rolls
        // back the whole transaction, so the store is left unchanged and the ding below is skipped —
        // a partial baseline is never observable. One ding on success, like clear()/put().
        queries.transaction {
            queries.deleteAll()
            entries.forEach {
                queries.put(it.key, it.assetId, it.state, it.attempt.toLong(), it.updatedAt)
            }
        }
        dings.tryEmit(Unit)
    }

    override suspend fun deleteByAssetId(assetId: String) {
        queries.deleteByAssetId(assetId)
        dings.tryEmit(Unit)
    }

    override suspend fun retainAssets(keep: Set<String>) {
        // Delete the complement, one assetId per statement, so the (possibly huge) keep-set is
        // never bound into a single SQL `IN`/`NOT IN` — it stays in Kotlin set math. The complement
        // is small in practice (only the assets removed since the last reconcile).
        val toDelete = queries.selectAllAssetIds().executeAsList().filterNot(keep::contains)
        if (toDelete.isEmpty()) return
        queries.transaction { toDelete.forEach { queries.deleteByAssetId(it) } }
        dings.tryEmit(Unit)
    }
}

/**
 * Constructs the generated database with its column adapters wired — the single place that
 * knows how `state` and `updatedAt` are encoded; construction sites only supply a driver.
 */
fun LedgerDatabase(driver: SqlDriver): LedgerDatabase = LedgerDatabase(
    driver,
    LedgerRow.Adapter(
        stateAdapter = EnumColumnAdapter(),
        updatedAtAdapter = EpochMillisAdapter,
    ),
)

private object EpochMillisAdapter : ColumnAdapter<Instant, Long> {
    override fun decode(databaseValue: Long): Instant = Instant.fromEpochMilliseconds(databaseValue)
    override fun encode(value: Instant): Long = value.toEpochMilliseconds()
}
