package app.snapsync.engine

import app.snapsync.model.LedgerAggregates
import app.snapsync.ports.LedgerStore
import app.snapsync.model.LedgerEntry
import app.snapsync.model.ResourceRole
import app.snapsync.model.DONE_STATES
import app.snapsync.model.NEEDS_JOB_STATES
import app.snapsync.model.LedgerState
import app.snapsync.model.PendingResource
import app.snapsync.model.TerminalOutcome

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.snapsync.engine.db.LedgerDatabase
import app.snapsync.engine.db.LedgerRow
import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * [LedgerStore] over the SQLDelight [LedgerDatabase] (schema: `Ledger.sq` — one table, key
 * primary key, an index on `assetId`). [recordUnlessSettled] is one guarded upsert statement, atomic on
 * its own; [aggregates] is one SQL round-trip, so its counts are mutually consistent. The driver
 * decides where the database lives (JVM sqlite for tests today; native driver with an App-Group path
 * is the iOS slice's).
 */
class SqlDelightLedgerStore(
    database: LedgerDatabase,
    // Defaulted, unlike the required ports elsewhere: a logger has a safe default (this tag), and
    // the parameter exists only so tests may silence or capture the store's diagnostics.
    private val log: Logger = Logger.withTag("SqlDelightLedgerStore"),
) : LedgerStore {

    private val queries = database.ledgerQueries

    private val dings = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<Unit> = dings

    override suspend fun get(key: String): LedgerEntry? =
        queries.get(key, ::toEntry).executeAsOneOrNull()

    override suspend fun entryForDestination(destinationPath: String): LedgerEntry? =
        queries.selectByDestinationPath(destinationPath, ::toEntry).executeAsOneOrNull()

    /** The one full-row projection mapper, so a column added to the row is added in one place. */
    @Suppress("LongParameterList")
    private fun toEntry(
        key: String,
        assetId: String,
        state: LedgerState,
        attempt: Long,
        eventId: String,
        creationDate: String,
        role: String,
        contentType: String,
        filename: String,
        absent: Long,
        destinationPath: String?,
    ) = LedgerEntry(
        key, assetId, state, attempt.toInt(), eventId,
        creationDate = creationDate,
        role = roleOrNull(role),
        contentType = contentType,
        originalFilename = filename,
        absent = absent != 0L,
        destinationPath = destinationPath,
    )

    /**
     * The guarded record write: the upsert and a `changes()` read in ONE transaction, the same shape as
     * [markTerminal] — the statement carries the done-state guard, and the database says whether it applied.
     * Dings only when it did: a declined write changed no truth.
     */
    override suspend fun recordUnlessSettled(entry: LedgerEntry): Boolean {
        val applied = queries.transactionWithResult {
            queries.recordUnlessSettled(
                entry.key, entry.assetId, entry.state, entry.attempt.toLong(), entry.eventId,
                entry.creationDate, entry.role?.wire ?: "", entry.contentType, entry.originalFilename,
                if (entry.absent) 1L else 0L, entry.destinationPath, DONE_STATES,
            )
            queries.changedRows().executeAsOne() > 0L
        }
        if (applied) dings.tryEmit(Unit)
        return applied
    }

    /**
     * Every entry through the same guarded upsert, inside ONE transaction, each applied/declined answer read in
     * that transaction. A throw from any statement rolls back the whole batch, so a walk's discoveries are
     * never partly recorded. One ding for the batch, and only if something applied.
     */
    override suspend fun recordAllUnlessSettled(entries: List<LedgerEntry>): Int {
        if (entries.isEmpty()) return 0
        val applied = queries.transactionWithResult {
            entries.count { entry ->
                queries.recordUnlessSettled(
                    entry.key, entry.assetId, entry.state, entry.attempt.toLong(), entry.eventId,
                    entry.creationDate, entry.role?.wire ?: "", entry.contentType, entry.originalFilename,
                    if (entry.absent) 1L else 0L, entry.destinationPath, DONE_STATES,
                )
                queries.changedRows().executeAsOne() > 0L
            }
        }
        if (applied > 0) dings.tryEmit(Unit)
        return applied
    }

    override suspend fun manifestRows(): List<LedgerEntry> =
        // `state` is read from the row rather than asserted. Nothing is bound: the query is not
        // state-scoped, because the manifest declares intent (capability `device-manifest`).
        queries.selectManifestRows { key, assetId, state, creationDate, role, contentType, filename ->
            LedgerEntry(
                key = key,
                assetId = assetId,
                state = state,
                attempt = 0,
                eventId = "", // provenance is irrelevant to the projection; the window decides
                creationDate = creationDate,
                role = roleOrNull(role),
                contentType = contentType,
                originalFilename = filename,
            )
        }.executeAsList()

    override suspend fun backfillManifestDetail(entry: LedgerEntry) {
        // One UPDATE matching the '' sentinel only — a row already enriched is untouched by the
        // WHERE clause, so the sweep is idempotent by construction and cannot clobber a good value.
        queries.backfillManifestDetail(
            creationDate = entry.creationDate,
            role = entry.role?.wire ?: "",
            contentType = entry.contentType,
            originalFilename = entry.originalFilename,
            key = entry.key,
        )
    }

    override suspend fun aggregates(): LedgerAggregates =
        queries.aggregates(DONE_STATES) { pending, completed ->
            LedgerAggregates(pending.toInt(), completed.toInt())
        }.executeAsOne()

    override suspend fun pendingResources(): List<PendingResource> =
        queries.selectPending(DONE_STATES) { assetId, key -> PendingResource(assetId, key) }.executeAsList()

    /**
     * The guarded terminal write. One `UPDATE` and one `changes()` read in ONE transaction — asking the
     * database what it just did, in the same transaction, is what makes "did this apply?" answerable
     * against a writer that takes no lock. Copied deliberately from `SqlDelightDownloadStore.applied`,
     * which solved the identical problem for PhotoKit's change and completion blocks.
     *
     * Non-suspending, and it dings only when it applied: a write that matched nothing changed no truth,
     * so waking every watcher to re-read an unchanged store would be noise.
     */
    override fun markTerminal(key: String, outcome: TerminalOutcome): Boolean {
        val applied = queries.transactionWithResult {
            queries.markTerminal(outcome.state, key)
            queries.changedRows().executeAsOne() > 0L
        }
        if (applied) dings.tryEmit(Unit)
        return applied
    }

    override suspend fun rowsNeedingJob(): List<LedgerEntry> =
        queries.selectNeedingJob(NEEDS_JOB_STATES, ::toEntry).executeAsList()

    override suspend fun requestedKeys(): Set<String> =
        queries.selectRequestedKeys().executeAsList().toSet()

    override suspend fun clear() {
        queries.deleteAll()
        dings.tryEmit(Unit)
    }

    override suspend fun demoteRequested() {
        queries.demoteRequested()
        dings.tryEmit(Unit)
    }

    override suspend fun resetTo(entries: List<LedgerEntry>) {
        // One transaction: delete-all then insert each. If any statement throws, SQLDelight rolls
        // back the whole transaction, so the store is left unchanged and the ding below is skipped —
        // a partial baseline is never observable. One ding on success, like clear(). A plain insert: after
        // deleteAll nothing can conflict, and the reset family applies no precedence.
        queries.transaction {
            queries.deleteAll()
            entries.forEach {
                queries.insert(
                    it.key, it.assetId, it.state, it.attempt.toLong(), it.eventId,
                    it.creationDate, it.role?.wire ?: "", it.contentType, it.originalFilename,
                    if (it.absent) 1L else 0L, it.destinationPath,
                )
            }
        }
        dings.tryEmit(Unit)
    }

    override suspend fun deleteKeys(keys: Collection<String>) {
        if (keys.isEmpty()) return
        // One transaction, chunked: an IN list is one bind variable per key, and a walk can name more rows
        // than a driver will bind. Dings only when a row went — a delete that matched nothing changed no truth.
        val deleted = queries.transactionWithResult {
            keys.toSet().chunked(KEY_CHUNK).sumOf { chunk ->
                queries.deleteKeys(chunk)
                queries.changedRows().executeAsOne()
            }
        }
        if (deleted > 0L) dings.tryEmit(Unit)
    }

    override suspend fun clearAbsenceMarks() {
        val cleared = queries.transactionWithResult {
            queries.clearAbsenceMarks()
            queries.changedRows().executeAsOne()
        }
        if (cleared > 0L) {
            // Positively observable, like the eventId backfill: the steady-state no-op stays silent.
            log.i { "absence marks cleared on $cleared row(s) an earlier build marked" }
            dings.tryEmit(Unit)
        }
    }

    /** `""` is the not-yet-enriched sentinel; every other value is a wire token the enum knows. */
    private fun roleOrNull(wire: String): ResourceRole? =
        ResourceRole.entries.firstOrNull { it.wire == wire }

    override suspend fun backfillEventId(eventId: String) {
        // One UPDATE matching the '' sentinel only — rows already carrying a real eventId are
        // untouched by the WHERE clause, so the sweep is idempotent by construction. The swept
        // count is logged when non-zero so the sweep is POSITIVELY observable on device
        // (`debug.log` is the extension's only observability); the steady-state no-op stays silent.
        val swept = queries.backfillEventId(eventId).value
        if (swept > 0) log.i { "eventId backfill: swept $swept row(s)" }
        dings.tryEmit(Unit)
    }
}

/** Keys per `deleteKeys` statement — well under every driver's bind-variable limit. */
private const val KEY_CHUNK = 500

/**
 * Constructs the generated database with its column adapters wired — the single place that
 * knows how `state` is encoded; construction sites only supply a driver.
 */
fun LedgerDatabase(driver: SqlDriver): LedgerDatabase = LedgerDatabase(
    driver,
    LedgerRow.Adapter(
        stateAdapter = EnumColumnAdapter(),
    ),
)
