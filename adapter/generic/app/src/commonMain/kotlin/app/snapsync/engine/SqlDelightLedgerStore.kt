package app.snapsync.engine

import app.snapsync.model.LedgerAggregates
import app.snapsync.ports.LedgerStore
import app.snapsync.model.LedgerEntry
import app.snapsync.model.ResourceRole
import app.snapsync.model.DONE_STATES
import app.snapsync.model.NEEDS_JOB_STATES
import app.snapsync.model.LedgerState
import app.snapsync.model.PendingResource

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
 * primary key, an index on `assetId`). [put] is the single `INSERT OR REPLACE` statement, atomic on
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

    override suspend fun put(entry: LedgerEntry) {
        queries.put(
            entry.key, entry.assetId, entry.state, entry.attempt.toLong(), entry.eventId,
            entry.creationDate, entry.role?.wire ?: "", entry.contentType, entry.originalFilename,
            if (entry.absent) 1L else 0L, entry.destinationPath,
        )
        dings.tryEmit(Unit)
    }

    override suspend fun completedManifestRows(): List<LedgerEntry> =
        // `state` is read from the row rather than asserted: the predicate now binds DONE_STATES, so
        // hardcoding COMPLETED here would become a lie the moment a second settled state exists.
        queries.selectCompletedManifestRows(DONE_STATES) { key, assetId, state, creationDate, role, contentType, filename ->
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
    override fun markTerminal(key: String, state: LedgerState): Boolean {
        val applied = queries.transactionWithResult {
            queries.markTerminal(state, key)
            queries.changedRows().executeAsOne() > 0L
        }
        if (applied) dings.tryEmit(Unit)
        return applied
    }

    override suspend fun uploadedRows(): List<LedgerEntry> =
        queries.selectUploaded(::toEntry).executeAsList()

    override suspend fun rowsNeedingJob(limit: Int): List<LedgerEntry> =
        queries.selectNeedingJob(NEEDS_JOB_STATES, limit.toLong(), ::toEntry).executeAsList()

    override suspend fun promoteUploaded(key: String): Boolean {
        val applied = queries.transactionWithResult {
            queries.promoteUploaded(key)
            queries.changedRows().executeAsOne() > 0L
        }
        if (applied) dings.tryEmit(Unit)
        return applied
    }

    override suspend fun requestedKeys(): Set<String> =
        queries.selectRequestedKeys().executeAsList().toSet()

    override suspend fun clear() {
        queries.deleteAll()
        dings.tryEmit(Unit)
    }

    override suspend fun clearRequested() {
        queries.deleteRequested()
        dings.tryEmit(Unit)
    }

    override suspend fun resetTo(entries: List<LedgerEntry>) {
        // One transaction: delete-all then insert each. If any statement throws, SQLDelight rolls
        // back the whole transaction, so the store is left unchanged and the ding below is skipped —
        // a partial baseline is never observable. One ding on success, like clear()/put().
        queries.transaction {
            queries.deleteAll()
            entries.forEach {
                queries.put(
                    it.key, it.assetId, it.state, it.attempt.toLong(), it.eventId,
                    it.creationDate, it.role?.wire ?: "", it.contentType, it.originalFilename,
                    if (it.absent) 1L else 0L, it.destinationPath,
                )
            }
        }
        dings.tryEmit(Unit)
    }

    override suspend fun markAbsent(assetId: String) {
        // An indexed UPDATE over one asset — no keep-set, so no bind-variable limit to work around, which
        // is what the deleted `retainAssets` needed its per-straggler loop for.
        queries.markAbsent(assetId)
        dings.tryEmit(Unit)
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
