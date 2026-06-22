package app.snapsync.engine

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * One key's durable upload memory. The ledger is the engine's only state: per-resource entries
 * keyed by [Resource.filename], holding the [assetId] the resource belongs to (an opaque grouping
 * id, several resources of one photo share it), the last recorded lifecycle [state], the [attempt]
 * it belongs to, the content-identity [version] it was recorded for, and [updatedAt] — when the
 * record operation ran (stamped by [LedgerWriter], never by callers or backends).
 */
class LedgerEntry(
    val key: String,
    val assetId: String,
    val state: LedgerState,
    val attempt: Int,
    val version: String,
    val updatedAt: Instant,
) {
    override fun equals(other: Any?): Boolean = other is LedgerEntry &&
        key == other.key && assetId == other.assetId && state == other.state &&
        attempt == other.attempt && version == other.version && updatedAt == other.updatedAt

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String =
        "LedgerEntry($key, assetId=$assetId, $state, attempt=$attempt, version=$version, updatedAt=$updatedAt)"
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
 * The ledger's lifetime truth in one snapshot-consistent read, counted by **photo (assetId), not
 * resource row**: [pending] = photos with any non-`COMPLETED` resource, [completed] = photos whose
 * resources are all `COMPLETED`, [newestCompletionAt] = the newest fully-completed photo's latest
 * [LedgerEntry.updatedAt], null when no photo is fully completed.
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
     * Delete every row whose [LedgerEntry.assetId] equals [assetId]. The backend matches by
     * equality and never interprets the value — `assetId` is a second opaque grouping field (it
     * does not know what an "asset" means). Dings [changes] like a [put].
     */
    suspend fun deleteByAssetId(assetId: String)

    /**
     * Delete every row whose [LedgerEntry.assetId] is **not** in [keep] (retain the intersection).
     * Dings [changes] like a [put]. The keep-set is used only to compute the complement — it is
     * never bound into a single SQL statement, so an arbitrarily large set stays within driver
     * bind-variable limits.
     */
    suspend fun retainAssets(keep: Set<String>)
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

    suspend fun recordRequested(key: String, assetId: String, attempt: Int, version: String) =
        record(key, assetId, LedgerState.REQUESTED, attempt, version)

    suspend fun recordCompleted(key: String, assetId: String, attempt: Int, version: String) =
        record(key, assetId, LedgerState.COMPLETED, attempt, version)

    suspend fun recordFailed(key: String, assetId: String, attempt: Int, version: String) =
        record(key, assetId, LedgerState.FAILED, attempt, version)

    /**
     * Prune every row for [assetId] — a sync write by the single writer (distinct from the app-side
     * [LedgerBackend.clear] reset). At the writer layer it stamps no `updatedAt` and consults no
     * engine state; it just removes. Exposed only here on the writer, never on [LedgerReader], so
     * read-only holders cannot prune.
     */
    suspend fun deleteByAssetId(assetId: String) = backend.deleteByAssetId(assetId)

    /** Prune every row whose assetId is not in [keep] (writer-only; see [deleteByAssetId]). */
    suspend fun retainAssets(keep: Set<String>) = backend.retainAssets(keep)

    private suspend fun record(key: String, assetId: String, state: LedgerState, attempt: Int, version: String) =
        backend.put(LedgerEntry(key, assetId, state, attempt, version, clock.now()))
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
