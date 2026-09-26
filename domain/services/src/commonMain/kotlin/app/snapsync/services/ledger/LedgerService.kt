package app.snapsync.services.ledger

import app.snapsync.services.upload.TransferRecord
import app.snapsync.model.AssetId
import app.snapsync.model.LedgerAggregates
import app.snapsync.model.LedgerEntry
import app.snapsync.model.ResourceRole
import app.snapsync.model.DONE_STATES
import app.snapsync.model.NEEDS_JOB_STATES
import app.snapsync.model.LedgerState
import app.snapsync.model.PendingResource
import app.snapsync.model.TerminalOutcome

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.snapsync.ports.Databases
import app.snapsync.services.databases.AssetIdColumnAdapter
import app.snapsync.services.databases.openOwned
import app.snapsync.services.ledger.db.LedgerDatabase
import app.snapsync.services.ledger.db.LedgerRow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** The upload ledger's database file — runtime identity (`docs/architecture.md`, section 9): a device holds it. */
const val LEDGER_DB_NAME: String = "ledger.db"

/**
 * The upload ledger (capability `photo-sharing`): [LedgerService] over the SQLDelight [LedgerDatabase] (schema:
 * `Ledger.sq` — one table, key primary key, an index on `assetId`). [recordUnlessSettled] is one guarded upsert
 * statement, atomic on its own; [aggregates] is one SQL round-trip, so its counts are mutually consistent.
 *
 * **Opened on first use, through [Databases]**, never at construction: building the composition opens no
 * database (a locked background launch must not be forced into one early). Either process may open it
 * read-write and migrate it — the ledger is shared by the app and the upload extension, one guarded writer per
 * kind of write. A failed open throws [app.snapsync.services.databases.DatabaseUnavailable] from that use and is
 * retried on the next: only a success is kept.
 */
class LedgerService(
    databases: Databases,
) : TransferRecord {

    private val queries by lazy { LedgerDatabase(databases.openOwned(LEDGER_DB_NAME, LedgerDatabase.Schema)).ledgerQueries }

    private val dings = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val changes: Flow<Unit> = dings

    /**
     * The row for [key], or null when there is none.
     *
     * Absence: null means "no such row", and ONLY that — a backend that cannot read throws rather
     * than answering empty, so this seam never has to encode "could not tell". That is what lets a
     * caller treat null as a fact about the ledger instead of a fact about the storage.
     *
     * Not on [TransferRecord]: no transport reads a row by key since the v1 last-segment fallback was retired
     * (decision record `changes/retire-legacy-key-fallback`).
     */
    suspend fun get(key: String): LedgerEntry? =
        queries.get(key, ::toEntry).executeAsOneOrNull()

    override suspend fun entryForDestination(destinationPath: String): LedgerEntry? =
        queries.selectByDestinationPath(destinationPath, ::toEntry).executeAsOneOrNull()

    /** The one full-row projection mapper, so a column added to the row is added in one place. */
    @Suppress("LongParameterList")
    private fun toEntry(
        key: String,
        assetId: AssetId,
        state: LedgerState,
        creationDate: String,
        role: String,
        contentType: String,
        filename: String,
        destinationPath: String?,
    ) = LedgerEntry(
        key, assetId, state,
        creationDate = creationDate,
        role = roleOrNull(role),
        contentType = contentType,
        originalFilename = filename,
        destinationPath = destinationPath,
    )

    /**
     * The guarded record write: the upsert and a `changes()` read in ONE transaction, the same shape as
     * [markTerminal] — the statement carries the done-state guard, and the database says whether it applied.
     * Dings only when it did: a declined write changed no truth.
     */
    suspend fun recordUnlessSettled(entry: LedgerEntry): Boolean {
        val applied = queries.transactionWithResult {
            queries.recordUnlessSettled(
                entry.key, entry.assetId, entry.state,
                entry.creationDate, entry.role?.wire ?: "", entry.contentType, entry.originalFilename,
                entry.destinationPath, DONE_STATES,
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
    suspend fun recordAllUnlessSettled(entries: List<LedgerEntry>): Int {
        if (entries.isEmpty()) return 0
        val applied = queries.transactionWithResult {
            entries.count { entry ->
                queries.recordUnlessSettled(
                    entry.key, entry.assetId, entry.state,
                    entry.creationDate, entry.role?.wire ?: "", entry.contentType, entry.originalFilename,
                    entry.destinationPath, DONE_STATES,
                )
                queries.changedRows().executeAsOne() > 0L
            }
        }
        if (applied > 0) dings.tryEmit(Unit)
        return applied
    }

    /**
     * The rows the **device manifest** projects from (capability `photo-sharing`): every row,
     * whatever its upload state.
     *
     * Deliberately **not state-scoped**, and deliberately carrying no state adjective in its name. The
     * manifest declares what this member *intends to provide*, and that does not depend on how far a
     * resource's bytes have got — so a `DISCOVERED` row and a `COMPLETED` one are equally listed. This
     * read used to return only settled rows, and the stale word "completed" in its name outlived the
     * decision behind it: `docs/architecture.md` came to describe a manifest that declares intent while
     * `photo-sharing` still required the completed projection.
     *
     * It filters on nothing: a departed asset's rows — gone from the library, or de-selected under a partial
     * grant, in flight or not — are deleted by the walk that shows it gone, so every row is one this device
     * still holds. **Admission is the policy's**, applied by the projection: the
     * capture-date bounds, and with them the exclusion of a row whose `creationDate` is still bare, whose
     * empty value sorts before every real cutoff. Restating that here would be a second copy of an
     * admission rule (capability `photo-sharing`).
     */
    suspend fun manifestRows(): List<LedgerEntry> =
        // `state` is read from the row rather than asserted. Nothing is bound: the query is not
        // state-scoped, because the manifest declares intent (capability `photo-sharing`).
        queries.selectManifestRows { key, assetId, state, creationDate, role, contentType, filename ->
            LedgerEntry(
                key = key,
                assetId = assetId,
                state = state,
                creationDate = creationDate,
                role = roleOrNull(role),
                contentType = contentType,
                originalFilename = filename,
            )
        }.executeAsList()

    /**
     * Fill the manifest detail of one already-recorded row **without touching its state**,
     * and only while the row is still bare — so re-running is free and can never clobber a good value.
     *
     * The sweep for the two ways a row rests bare: it predates the 5.sqm migration, or the re-join
     * reconcile seeded it from a stored-file listing (filenames carry no capture date). A writer-family
     * operation like [deleteKeys]: only the single writer's cycle runs it.
     */
    suspend fun backfillManifestDetail(entry: LedgerEntry) {
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

    /**
     * The ledger's whole-store truth, counted by photo. It counts EVERY row — the join-time load seeds the
     * device's stored resources for any event — so it is not the status read: its callers are the
     * extension's "work remains" check and the diagnostic dump. Status reads [assetProgress].
     */
    suspend fun aggregates(): LedgerAggregates =
        queries.aggregates(DONE_STATES) { pending, completed ->
            LedgerAggregates(pending.toInt(), completed.toInt())
        }.executeAsOne()

    /**
     * Per photo, whether **every** row of that asset is done: `assetId → done`, one entry per asset the ledger
     * holds a row for (capability `photo-sharing`, "Per-asset progress read"). The same per-asset collapse
     * [aggregates] performs, un-counted, in one snapshot-consistent read. Status intersects it with the
     * admitted set the gallery counted for `N`; the ledger interprets nothing about admission.
     */
    suspend fun assetProgress(): Map<AssetId, Boolean> =
        queries.assetProgress(DONE_STATES) { assetId, notDone -> assetId to ((notDone ?: 0L) == 0L) }
            .executeAsList()
            .toMap()

    /**
     * The non-settled rows (the backlog) as [PendingResource]s. Returns exactly the rows whose state is
     * not in [app.snapsync.model.DONE_STATES], interpreting nothing else — the backend stays a dumb row
     * store, and *which* states are settled is decided once, in `model/`, not per query.
     */
    suspend fun pendingResources(): List<PendingResource> =
        queries.selectPending(DONE_STATES) { assetId, key -> PendingResource(assetId, key) }.executeAsList()

    /**
     * The guarded terminal write. One `UPDATE` and one `changes()` read in ONE transaction — asking the
     * database what it just did, in the same transaction, is what makes "did this apply?" answerable
     * against a writer that takes no lock. Copied deliberately from `DownloadService.applied`,
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

    /**
     * The rows that **need an upload job**, in a stable key order — the upload cycle's source of work
     * (capability `photo-sharing`).
     *
     * Returns exactly the rows whose state is in [app.snapsync.model.NEEDS_JOB_STATES], interpreting
     * nothing else: *which* states need a job is decided once, in `model/`, not per query. That set is
     * `DISCOVERED` — a key with no live job and no bytes on the backend, whether never attempted or returned
     * there by a failure.
     *
     * **Unbounded, deliberately.** A cycle does bound its work — a first walk on a large library records a
     * row per outstanding resource, and enqueuing all of them would stage every one to disk — but it
     * bounds what it **resolves**, never what it reads, because a row needing a job is not yet the
     * admitted set (capability `photo-sharing`). A bound here would starve: rows come back in a
     * stable key order, so rows the membership's current policy excludes, sorting ahead of admitted ones,
     * would fill the slice on every cycle and the admitted work further down would never be reached. The
     * scan is local and indexed; the platform round-trip the bound protects is the caller's to make.
     */
    suspend fun rowsNeedingJob(): List<LedgerEntry> =
        queries.selectNeedingJob(NEEDS_JOB_STATES, ::toEntry).executeAsList()

    /**
     * Delete every row — a deliberate reset (the app re-provisioning config), not a sync write.
     * Dings [changes] so watchers re-read the now-empty truth.
     */
    suspend fun clear() {
        queries.deleteAll()
        dings.tryEmit(Unit)
    }

    /**
     * Atomically replace the entire store with [entries] (delete-all then insert-all in one
     * transaction): either all prior rows go and all [entries] land, or — on failure — the store is
     * left exactly as it was (no partial baseline is ever observable). Entries are stored verbatim
     * (the caller supplies `state`; no clock stamping here). Dings [changes]
     * **once** on success. It applies no precedence — a settled row is replaced like any other. This is a
     * reset-family op (alongside [clear]) — the app-side
     * join seed uses it; it is **not** a per-key record, and it is owned by that membership use-case
     * (capability `photo-sharing`, "Reader and writer capability split").
     */
    suspend fun resetTo(entries: List<LedgerEntry>) {
        // One transaction: delete-all then insert each. If any statement throws, SQLDelight rolls
        // back the whole transaction, so the store is left unchanged and the ding below is skipped —
        // a partial baseline is never observable. One ding on success, like clear(). A plain insert: after
        // deleteAll nothing can conflict, and the reset family applies no precedence.
        queries.transaction {
            queries.deleteAll()
            entries.forEach {
                queries.insert(
                    it.key, it.assetId, it.state,
                    it.creationDate, it.role?.wire ?: "", it.contentType, it.originalFilename,
                    it.destinationPath,
                )
            }
        }
        dings.tryEmit(Unit)
    }

    /**
     * Delete exactly the rows whose key is among [keys], whatever their state, and no other — the one row
     * deletion a cycle performs (capability `photo-sharing`, "Deletion is a presence diff over an authoritative
     * walk").
     *
     * **Key-scoped, never asset-scoped.** Several resources of one photo share an `assetId` and hold per-key
     * states, and every caller holds evidence about individual rows: a key that resolved to nothing, or a row
     * an authoritative walk did not return. An asset-scoped delete driven by a key-grained read reaches rows
     * the read never selected — a Live Photo's `COMPLETED` primary, deleted because its paired video's key
     * failed to resolve under a partial grant.
     *
     * Writes nothing and dings nothing when none of [keys] has a row. Accepts more keys than one storage
     * statement binds. A writer-family operation: only the single writer's cycle runs it.
     */
    suspend fun deleteKeys(keys: Collection<String>) {
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

    // The counter itself is maintained by `Ledger.sq`'s triggers, inside each write's own transaction; these
    // are its one read and the one explicit advance (capability `photo-sharing`).
    suspend fun manifestVersion(): Long = queries.selectManifestVersion().executeAsOne()

    /**
     * Advance the manifest version by one, for the one projection input that lives outside this store: the
     * membership's policy bounds, whose writer (the reconfigure save) calls this **after** its config save has
     * landed (capability `manage-membership`). Dings nothing: no row changed.
     */
    suspend fun bumpManifestVersion() {
        queries.bumpManifestVersion()
    }

    /** `""` is the not-yet-enriched sentinel; every other value is a wire token the enum knows. */
    private fun roleOrNull(wire: String): ResourceRole? =
        ResourceRole.entries.firstOrNull { it.wire == wire }

}

/** Keys per `deleteKeys` statement — well under every driver's bind-variable limit. */
private const val KEY_CHUNK = 500

/**
 * Constructs the generated database with its column adapters wired — the single place that
 * knows how `state` is encoded.
 */
internal fun LedgerDatabase(driver: SqlDriver): LedgerDatabase = LedgerDatabase(
    driver,
    LedgerRow.Adapter(
        assetIdAdapter = AssetIdColumnAdapter,
        stateAdapter = EnumColumnAdapter(),
    ),
)
