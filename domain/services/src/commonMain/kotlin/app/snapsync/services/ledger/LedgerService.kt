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

/** The upload ledger's database file — runtime identity (`docs/architecture.md`, section 9): a device holds it. */
const val LEDGER_DB_NAME: String = "ledger.db"

/**
 * The upload ledger (capability `photo-sharing`): [LedgerService] over the SQLDelight [LedgerDatabase] (schema:
 * `Ledger.sq` — one table, `(eventId, key)` primary key, an index on `assetId`). [recordUnlessSettled] is one
 * guarded upsert statement, atomic on its own; [aggregates] is one SQL round-trip, so its counts are mutually
 * consistent.
 *
 * **Every row belongs to one event, and every read and write is scoped to the joined one** — [joinedEvent], the
 * process's config (change `event-scoped-local-state`). Each event holds its own bytes, so a row is true for its
 * event only; scoping in the statement is what makes a row of another event inert rather than a lifecycle rule
 * every leave had to keep. With nothing joined, every read answers empty and every write applies nothing. The
 * reset family alone names its event explicitly ([resetTo], [purgeExcept]), because the join it belongs to runs
 * before the joined config is saved.
 *
 * **Opened on first use, through [Databases]**, never at construction: building the composition opens no
 * database (a locked background launch must not be forced into one early). Either process may open it
 * read-write and migrate it — the ledger is shared by the app and the upload extension, one guarded writer per
 * kind of write. A failed open throws [app.snapsync.services.databases.DatabaseUnavailable] from that use and is
 * retried on the next: only a success is kept.
 */
class LedgerService(
    databases: Databases,
    /** The event this process is joined to, or null; read at every call, never cached. */
    private val joinedEvent: () -> String?,
) : TransferRecord {

    private val queries by lazy { LedgerDatabase(databases.openOwned(LEDGER_DB_NAME, LedgerDatabase.Schema)).ledgerQueries }

    // The event whose parked rows this process has adopted. A second adoption is a no-op write, so a race between
    // two callers costs one statement, never a row.
    private var adoptedFor: String? = null

    /**
     * The joined event, after adopting into it the rows 12.sqm parked under the empty event — once per event per
     * process. Null when nothing is joined: the parked rows then wait, and the next join's purge removes them.
     */
    private fun event(): String? {
        val eventId = joinedEvent() ?: return null
        if (adoptedFor != eventId) {
            queries.adoptParked(eventId)
            adoptedFor = eventId
        }
        return eventId
    }

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
    suspend fun get(key: String): LedgerEntry? {
        val eventId = event() ?: return null
        return queries.get(eventId, key, ::toEntry).executeAsOneOrNull()
    }

    override suspend fun entryForDestination(destinationPath: String): LedgerEntry? {
        val eventId = event() ?: return null
        return queries.selectByDestinationPath(eventId, destinationPath, ::toEntry).executeAsOneOrNull()
    }

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
     */
    suspend fun recordUnlessSettled(entry: LedgerEntry): Boolean {
        val eventId = event() ?: return false
        val applied = queries.transactionWithResult {
            queries.recordUnlessSettled(
                eventId, entry.key, entry.assetId, entry.state,
                entry.creationDate, entry.role?.wire ?: "", entry.contentType, entry.originalFilename,
                entry.destinationPath, DONE_STATES,
            )
            queries.changedRows().executeAsOne() > 0L
        }
        return applied
    }

    /**
     * Every entry through the same guarded upsert, inside ONE transaction, each applied/declined answer read in
     * that transaction. A throw from any statement rolls back the whole batch, so a walk's discoveries are
     * never partly recorded.
     */
    suspend fun recordAllUnlessSettled(entries: List<LedgerEntry>): Int {
        if (entries.isEmpty()) return 0
        val eventId = event() ?: return 0
        val applied = queries.transactionWithResult {
            entries.count { entry ->
                queries.recordUnlessSettled(
                    eventId, entry.key, entry.assetId, entry.state,
                    entry.creationDate, entry.role?.wire ?: "", entry.contentType, entry.originalFilename,
                    entry.destinationPath, DONE_STATES,
                )
                queries.changedRows().executeAsOne() > 0L
            }
        }
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
        // `state` is read from the row rather than asserted. Only the event is bound: the query is not
        // state-scoped, because the manifest declares intent (capability `photo-sharing`).
        queries.selectManifestRows(event() ?: return emptyList()) { key, assetId, state, creationDate, role, contentType, filename ->
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
     * The sweep for a row resting bare: the re-join reconcile seeded it from a stored-file listing (filenames carry
     * no capture date). A writer-family
     * operation like [deleteKeys]: only the single writer's cycle runs it.
     */
    suspend fun backfillManifestDetail(entry: LedgerEntry) {
        val eventId = event() ?: return
        // One UPDATE matching the '' sentinel only — a row already enriched is untouched by the
        // WHERE clause, so the sweep is idempotent by construction and cannot clobber a good value.
        queries.backfillManifestDetail(
            eventId = eventId,
            creationDate = entry.creationDate,
            role = entry.role?.wire ?: "",
            contentType = entry.contentType,
            originalFilename = entry.originalFilename,
            key = entry.key,
        )
    }

    /**
     * The joined event's rows, counted by photo. It counts every row of the event, admitted or not — the join-time
     * load seeds every resource the backend stores for it — so it is not the status read: its callers are the
     * extension's "work remains" check and the diagnostic dump. Status reads [assetProgress].
     */
    suspend fun aggregates(): LedgerAggregates {
        val eventId = event() ?: return LedgerAggregates(0, 0)
        return queries.aggregates(eventId = eventId, doneStates = DONE_STATES) { pending, completed ->
            LedgerAggregates(pending.toInt(), completed.toInt())
        }.executeAsOne()
    }

    /**
     * Per photo, whether **every** row of that asset is done: `assetId → done`, one entry per asset the ledger
     * holds a row for (capability `photo-sharing`, "Per-asset progress read"). The same per-asset collapse
     * [aggregates] performs, un-counted, in one snapshot-consistent read. Status intersects it with the
     * admitted set the gallery counted for `N`; the ledger interprets nothing about admission.
     */
    suspend fun assetProgress(): Map<AssetId, Boolean> =
        queries.assetProgress(doneStates = DONE_STATES, eventId = event() ?: return emptyMap()) { assetId, notDone ->
            assetId to (notDone == 0L)
        }
            .executeAsList()
            .toMap()

    /**
     * The non-settled rows (the backlog) as [PendingResource]s. Returns exactly the rows whose state is
     * not in [app.snapsync.model.DONE_STATES], interpreting nothing else — the backend stays a dumb row
     * store, and *which* states are settled is decided once, in `model/`, not per query.
     */
    suspend fun pendingResources(): List<PendingResource> =
        queries.selectPending(event() ?: return emptyList(), DONE_STATES) { assetId, key -> PendingResource(assetId, key) }
            .executeAsList()

    /**
     * The guarded terminal write. One `UPDATE` and one `changes()` read in ONE transaction — asking the
     * database what it just did, in the same transaction, is what makes "did this apply?" answerable
     * against a writer that takes no lock. Copied deliberately from `DownloadService.applied`,
     * which solved the identical problem for PhotoKit's change and completion blocks. Non-suspending.
     */
    override fun markTerminal(key: String, outcome: TerminalOutcome): Boolean {
        val eventId = event() ?: return false
        return queries.transactionWithResult {
            queries.markTerminal(state = outcome.state, eventId = eventId, key = key)
            queries.changedRows().executeAsOne() > 0L
        }
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
        queries.selectNeedingJob(event() ?: return emptyList(), NEEDS_JOB_STATES, ::toEntry).executeAsList()

    /**
     * Delete every row of every event — the device-state reset, not a sync write.
     */
    suspend fun clear() {
        queries.deleteAll()
    }

    /**
     * Delete every row of any event but [eventId] — the join's purge (change `event-scoped-local-state`). Such rows
     * are already inert, since every read is scoped to the joined event, so this is housekeeping: a purge that fails
     * costs space, never a suppressed upload. Rows of [eventId] are kept, so a failed listing at a rejoin keeps what
     * this device last knew of the event.
     */
    suspend fun purgeExcept(eventId: String) {
        queries.purgeExcept(eventId)
    }

    /**
     * Atomically make [entries] the rows of [eventId], and purge every other event's rows, in one transaction:
     * either all of it lands, or — on failure — the store is left exactly as it was (no partial baseline is ever
     * observable). Entries are stored verbatim (the caller supplies `state`; no clock stamping here). It applies no
     * precedence — a settled row is replaced like any other. This is a reset-family op (alongside [clear] and
     * [purgeExcept]) — the join seed uses it, before the joined config is saved, which is why it names its event;
     * it is **not** a per-key record, and it is owned by that membership use-case (capability `photo-sharing`,
     * "Reader and writer capability split").
     */
    suspend fun resetTo(eventId: String, entries: List<LedgerEntry>) {
        // One transaction: purge, delete the event's rows, then insert each. If any statement throws, SQLDelight
        // rolls back the whole transaction, so the store is left unchanged — a partial baseline is never
        // observable. A plain insert: after deleteEvent nothing can conflict, and the reset family applies no
        // precedence.
        queries.transaction {
            queries.purgeExcept(eventId)
            queries.deleteEvent(eventId)
            entries.forEach {
                queries.insert(
                    eventId, it.key, it.assetId, it.state,
                    it.creationDate, it.role?.wire ?: "", it.contentType, it.originalFilename,
                    it.destinationPath,
                )
            }
        }
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
     * Writes nothing when none of [keys] has a row. Accepts more keys than one storage
     * statement binds. A writer-family operation: only the single writer's cycle runs it.
     */
    suspend fun deleteKeys(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val eventId = event() ?: return
        // One transaction, chunked: an IN list is one bind variable per key, and a walk can name more rows
        // than a driver will bind.
        queries.transaction {
            keys.toSet().chunked(KEY_CHUNK).forEach { chunk -> queries.deleteKeys(eventId, chunk) }
        }
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
