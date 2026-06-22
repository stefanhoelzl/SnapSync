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
        queries.get(key) { _, state, attempt, version, updatedAt ->
            LedgerEntry(key, state, attempt.toInt(), version, updatedAt)
        }.executeAsOneOrNull()

    override suspend fun put(entry: LedgerEntry) {
        queries.put(entry.key, entry.state, entry.attempt.toLong(), entry.version, entry.updatedAt)
        dings.tryEmit(Unit)
    }

    override suspend fun aggregates(): LedgerAggregates =
        queries.aggregates { pending, completed, newestCompletionAt ->
            LedgerAggregates(pending.toInt(), completed.toInt(), newestCompletionAt)
        }.executeAsOne()

    override suspend fun clear() {
        queries.deleteAll()
        dings.tryEmit(Unit)
    }

    override suspend fun deleteByKeyPrefix(prefix: String) {
        if (prefix.isEmpty()) return
        // [prefix, successor): successor is prefix with its last char bumped by one, so it is the
        // least string greater than every key beginning with prefix — a half-open range scan.
        val successor = prefix.dropLast(1) + (prefix.last() + 1)
        queries.deleteByKeyPrefix(lo = prefix, hi = successor)
        dings.tryEmit(Unit)
    }

    override suspend fun retainKeys(keep: Set<String>) {
        // Delete the complement, one key per statement, so the (possibly huge) keep-set is never
        // bound into a single SQL `IN`/`NOT IN` — it stays in Kotlin set math. The complement is
        // small in practice (only the assets removed since the last reconcile).
        val toDelete = queries.selectAllKeys().executeAsList().filterNot(keep::contains)
        if (toDelete.isEmpty()) return
        queries.transaction { toDelete.forEach { queries.deleteKey(it) } }
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
