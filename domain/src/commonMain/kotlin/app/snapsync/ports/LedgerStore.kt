package app.snapsync.ports

import app.snapsync.model.LedgerAggregates
import app.snapsync.model.LedgerEntry
import app.snapsync.model.PendingResource
import kotlinx.coroutines.flow.Flow

/**
 * The ledger's storage seam — a dumb row store. Backends store entries verbatim: no
 * interpretation, no precedence, no clocks of their own, last write wins. [put] is a single-row
 * upsert and the unit of atomicity; record semantics live above, in [LedgerWriter], written once
 * for every backend. [changes] dings after every successful put — no payload, the only promise
 * is "re-read the truth", so conflation and missed signals are harmless by construction. The ding
 * is **in-process only**: the ledger is the extension's private upload memory with no cross-process
 * watcher, so backends post no cross-process (e.g. Darwin) notification.
 */
interface LedgerStore {
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
