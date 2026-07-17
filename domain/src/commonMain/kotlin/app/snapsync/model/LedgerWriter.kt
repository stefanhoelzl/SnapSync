package app.snapsync.model

/**
 * The ledger's single writer (one per platform, hosted with the engine), carrying the engine's
 * per-key read ([entry]). Each record operation upserts a complete, self-contained entry in one
 * backend [LedgerStore.put] — no operation depends on a prior read, so duplicate records converge
 * per key on state and attempt. The writer keeps no clock; the engine and backends are all
 * clock-free and store verbatim. Only the composition root that owns the engine ever constructs it.
 * Aggregates and change signals are deliberately absent from this per-key face; the extension's own
 * cycle reads them via [LedgerStore] directly.
 */
class LedgerWriter(
    private val backend: LedgerStore,
) {

    suspend fun entry(key: String): LedgerEntry? = backend.get(key)

    suspend fun recordRequested(key: String, assetId: String, attempt: Int) =
        record(key, assetId, LedgerState.REQUESTED, attempt)

    suspend fun recordCompleted(key: String, assetId: String, attempt: Int) =
        record(key, assetId, LedgerState.COMPLETED, attempt)

    suspend fun recordFailed(key: String, assetId: String, attempt: Int) =
        record(key, assetId, LedgerState.FAILED, attempt)

    /**
     * Prune every row for [assetId] — a sync write by the single writer (distinct from the app-side
     * [LedgerStore.clear] reset). At the writer layer it consults no engine state; it just
     * removes.
     */
    suspend fun deleteByAssetId(assetId: String) = backend.deleteByAssetId(assetId)

    /** Prune every row whose assetId is not in [keep] (writer-only; see [deleteByAssetId]). */
    suspend fun retainAssets(keep: Set<String>) = backend.retainAssets(keep)

    private suspend fun record(key: String, assetId: String, state: LedgerState, attempt: Int) =
        backend.put(LedgerEntry(key, assetId, state, attempt))
}
