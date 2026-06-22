package app.snapsync.engine

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * One key's durable upload memory. The ledger is the engine's only state: per-resource entries
 * keyed by [Resource.filename], holding the last recorded lifecycle [state], the [attempt] it
 * belongs to, the content-identity [version] it was recorded for, and [updatedAt] — when the
 * record operation ran (stamped by [LedgerWriter], never by callers or backends).
 */
class LedgerEntry(
    val key: String,
    val state: LedgerState,
    val attempt: Int,
    val version: String,
    val updatedAt: Instant,
) {
    override fun equals(other: Any?): Boolean = other is LedgerEntry &&
        key == other.key && state == other.state && attempt == other.attempt &&
        version == other.version && updatedAt == other.updatedAt

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String =
        "LedgerEntry($key, $state, attempt=$attempt, version=$version, updatedAt=$updatedAt)"
}

enum class LedgerState {
    /** Work was answered for this key — a hope; the engine cannot prove it was executed. */
    REQUESTED,

    /** The platform observed and reported a successful upload — a fact about the world. */
    COMPLETED,

    /** The platform reported a failed attempt; a retry was answered alongside. */
    FAILED,
}

/**
 * The ledger's lifetime truth in one snapshot-consistent read: [pending] = keys not yet proven
 * uploaded (anything non-`COMPLETED`), [completed] = keys with proof, [newestCompletionAt] =
 * the latest completion's [LedgerEntry.updatedAt], null when nothing has ever completed.
 */
class LedgerAggregates(
    val pending: Int,
    val completed: Int,
    val newestCompletionAt: Instant?,
) {
    override fun equals(other: Any?): Boolean = other is LedgerAggregates &&
        pending == other.pending && completed == other.completed &&
        newestCompletionAt == other.newestCompletionAt

    override fun hashCode(): Int = 31 * (31 * pending + completed) + newestCompletionAt.hashCode()

    override fun toString(): String =
        "LedgerAggregates(pending=$pending, completed=$completed, newestCompletionAt=$newestCompletionAt)"
}

/**
 * The ledger's storage seam — a dumb row store. Backends store entries verbatim: no
 * interpretation, no precedence, no clocks of their own, last write wins. [put] is a single-row
 * upsert and the unit of atomicity; record semantics live above, in [LedgerWriter], written once
 * for every backend. [changes] dings after every successful put — no payload, the only promise
 * is "re-read the truth", so conflation and missed signals are harmless by construction. Where
 * another process writes the store, feeding [changes] is that backend's concern (e.g. a Darwin
 * observer on iOS); the seam does not change.
 */
interface LedgerBackend {
    val changes: Flow<Unit>
    suspend fun get(key: String): LedgerEntry?
    suspend fun put(entry: LedgerEntry)
    suspend fun aggregates(): LedgerAggregates

    /**
     * Delete every row — a deliberate reset (the app re-provisioning config), not a sync write.
     * Dings [changes] like a [put] so watchers re-read the now-empty truth.
     */
    suspend fun clear()

    /**
     * Delete every row whose [key] begins with [prefix]. A verbatim key-string match — the backend
     * interprets no structure within a key (any asset/resource grouping a key encodes is the
     * caller's convention, unknown to the seam). Dings [changes] like a [put].
     */
    suspend fun deleteByKeyPrefix(prefix: String)

    /**
     * Delete every row whose [key] is **not** in [keep] (retain the intersection). Dings [changes]
     * like a [put]. The keep-set is used only to compute the complement — it is never bound into a
     * single SQL statement, so an arbitrarily large set stays within driver bind-variable limits.
     */
    suspend fun retainKeys(keep: Set<String>)
}

/**
 * Read-only, per-key face of the ledger — the engine's view. Handing a [LedgerWriter] out typed
 * as [LedgerReader] is the narrowing — type safety against accidental writes, by construction:
 * only the composition root that owns the engine ever constructs the writer. Aggregates and
 * change signals are deliberately absent here; they belong to [LedgerWatcher].
 */
open class LedgerReader(protected val backend: LedgerBackend) {
    suspend fun entry(key: String): LedgerEntry? = backend.get(key)
}

/**
 * The ledger's single writer (one per platform, hosted with the engine). Each record operation
 * upserts a complete, self-contained entry in one backend [LedgerBackend.put] — no operation
 * depends on a prior read, so duplicate records converge per key on state, attempt, and version;
 * only the [clock]-stamped timestamp moves forward. The writer is the single stamping point:
 * the engine stays clock-free and backends store verbatim.
 */
class LedgerWriter(
    backend: LedgerBackend,
    private val clock: Clock = Clock.System,
) : LedgerReader(backend) {

    suspend fun recordRequested(key: String, attempt: Int, version: String) =
        record(key, LedgerState.REQUESTED, attempt, version)

    suspend fun recordCompleted(key: String, attempt: Int, version: String) =
        record(key, LedgerState.COMPLETED, attempt, version)

    suspend fun recordFailed(key: String, attempt: Int, version: String) =
        record(key, LedgerState.FAILED, attempt, version)

    /**
     * Prune every row whose key begins with [prefix] — a sync write by the single writer (distinct
     * from the app-side [LedgerBackend.clear] reset). Unlike the record operations it stamps no
     * `updatedAt` and reads nothing first; it just removes. Exposed only here on the writer, never
     * on [LedgerReader], so read-only holders cannot prune.
     */
    suspend fun deleteByKeyPrefix(prefix: String) = backend.deleteByKeyPrefix(prefix)

    /** Prune every row whose key is not in [keep] (writer-only; see [deleteByKeyPrefix]). */
    suspend fun retainKeys(keep: Set<String>) = backend.retainKeys(keep)

    private suspend fun record(key: String, state: LedgerState, attempt: Int, version: String) =
        backend.put(LedgerEntry(key, state, attempt, version, clock.now()))
}

/**
 * The status-facing face of the ledger: a cold stream of [LedgerAggregates]. Every collection
 * starts with the current truth, then re-queries on each backend ding — collectors share
 * nothing. Dings are conflated (each re-query reads latest state, skipped signals lose nothing)
 * and equal consecutive aggregates are deduplicated. This is the only ledger type that surfaces
 * aggregates or change signals; per-key reads stay on [LedgerReader].
 */
class LedgerWatcher(private val backend: LedgerBackend) {

    val aggregates: Flow<LedgerAggregates> = flow {
        emit(backend.aggregates())
        backend.changes.conflate().collect { emit(backend.aggregates()) }
    }.distinctUntilChanged()
}
