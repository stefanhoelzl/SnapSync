package app.snapsync.engine

import kotlinx.coroutines.flow.Flow

/**
 * One key's durable upload memory. The ledger is the engine's only state: per-resource entries
 * keyed by [Resource.filename], holding the [assetId] the resource belongs to (an opaque grouping
 * id, several resources of one photo share it), the last recorded lifecycle [state], and the
 * [attempt] it belongs to. An uploaded resource is immutable, so a `COMPLETED` entry's mere
 * existence is the proof of upload; there is no content version, and the ledger keeps no timestamp.
 */
class LedgerEntry(
    val key: String,
    val assetId: String,
    val state: LedgerState,
    val attempt: Int,
) {
    override fun equals(other: Any?): Boolean = other is LedgerEntry &&
        key == other.key && assetId == other.assetId && state == other.state &&
        attempt == other.attempt

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String =
        "LedgerEntry($key, assetId=$assetId, $state, attempt=$attempt)"
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
 * resources are all `COMPLETED`.
 */
class LedgerAggregates(
    val pending: Int,
    val completed: Int,
) {
    override fun equals(other: Any?): Boolean = other is LedgerAggregates &&
        pending == other.pending && completed == other.completed

    override fun hashCode(): Int = 31 * pending + completed

    override fun toString(): String =
        "LedgerAggregates(pending=$pending, completed=$completed)"
}

/**
 * One outstanding resource: the [assetId] (photo) a non-`COMPLETED` [key] belongs to. The backlog
 * read returns these so a status projection can group outstanding resources by photo; the backend
 * never interprets them (it just reports the rows whose state is not `COMPLETED`).
 */
class PendingResource(val assetId: String, val key: String) {
    override fun equals(other: Any?): Boolean =
        other is PendingResource && assetId == other.assetId && key == other.key

    override fun hashCode(): Int = 31 * assetId.hashCode() + key.hashCode()

    override fun toString(): String = "PendingResource(assetId=$assetId, key=$key)"
}

/**
 * The ledger's storage seam — a dumb row store. Backends store entries verbatim: no
 * interpretation, no precedence, no clocks of their own, last write wins. [put] is a single-row
 * upsert and the unit of atomicity; record semantics live above, in [LedgerWriter], written once
 * for every backend. [changes] dings after every successful put — no payload, the only promise
 * is "re-read the truth", so conflation and missed signals are harmless by construction. The ding
 * is **in-process only**: the ledger is the extension's private upload memory with no cross-process
 * watcher, so backends post no cross-process (e.g. Darwin) notification.
 */
interface LedgerBackend {
    val changes: Flow<Unit>
    suspend fun get(key: String): LedgerEntry?
    suspend fun put(entry: LedgerEntry)
    suspend fun aggregates(): LedgerAggregates

    /**
     * The non-`COMPLETED` rows (the backlog) as [PendingResource]s. Returns exactly the rows whose
     * state is not `COMPLETED`, interpreting nothing else — the backend stays a dumb row store.
     */
    suspend fun pendingResources(): List<PendingResource>

    /**
     * Delete every row — a deliberate reset (the app re-provisioning config), not a sync write.
     * Dings [changes] like a [put] so watchers re-read the now-empty truth.
     */
    suspend fun clear()

    /**
     * Delete every `REQUESTED` row, leaving `COMPLETED` and `FAILED` rows untouched — an **app-side
     * reset-family** op (alongside [clear]), not a writer-only prune. The recovery for jobs the OS
     * wiped when the extension was disabled: those resources stay `REQUESTED`, the engine never
     * re-issues a `REQUESTED` key, and no API surfaces the vanished job — so clearing `REQUESTED` is
     * what lets the next discovery re-create them. Clearing **all** `REQUESTED` is correct because a
     * disable wipes **all** in-flight jobs at once. Dings [changes] once, like [clear].
     */
    suspend fun clearRequested()

    /**
     * Atomically replace the entire store with [entries] (delete-all then insert-all in one
     * transaction): either all prior rows go and all [entries] land, or — on failure — the store is
     * left exactly as it was (no partial baseline is ever observable). Entries are stored verbatim
     * (the caller supplies `state`; no clock stamping here). Dings [changes]
     * **once** on success, like a [put]. This is a reset-family op (alongside [clear]) — the app-side
     * join seed uses it; it is **not** a per-key record, so it does not breach the single-record-writer
     * invariant.
     */
    suspend fun resetTo(entries: List<LedgerEntry>)

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
 * change signals are deliberately absent from this per-key face; the extension's own cycle reads
 * them via [LedgerBackend] directly.
 */
open class LedgerReader(protected val backend: LedgerBackend) {
    suspend fun entry(key: String): LedgerEntry? = backend.get(key)
}

/**
 * The ledger's single writer (one per platform, hosted with the engine). Each record operation
 * upserts a complete, self-contained entry in one backend [LedgerBackend.put] — no operation
 * depends on a prior read, so duplicate records converge per key on state and attempt. The writer
 * keeps no clock; the engine and backends are all clock-free and store verbatim.
 */
class LedgerWriter(
    backend: LedgerBackend,
) : LedgerReader(backend) {

    suspend fun recordRequested(key: String, assetId: String, attempt: Int) =
        record(key, assetId, LedgerState.REQUESTED, attempt)

    suspend fun recordCompleted(key: String, assetId: String, attempt: Int) =
        record(key, assetId, LedgerState.COMPLETED, attempt)

    suspend fun recordFailed(key: String, assetId: String, attempt: Int) =
        record(key, assetId, LedgerState.FAILED, attempt)

    /**
     * Prune every row for [assetId] — a sync write by the single writer (distinct from the app-side
     * [LedgerBackend.clear] reset). At the writer layer it consults no engine state; it just
     * removes. Exposed only here on the writer, never on [LedgerReader], so read-only holders cannot
     * prune.
     */
    suspend fun deleteByAssetId(assetId: String) = backend.deleteByAssetId(assetId)

    /** Prune every row whose assetId is not in [keep] (writer-only; see [deleteByAssetId]). */
    suspend fun retainAssets(keep: Set<String>) = backend.retainAssets(keep)

    private suspend fun record(key: String, assetId: String, state: LedgerState, attempt: Int) =
        backend.put(LedgerEntry(key, assetId, state, attempt))
}
