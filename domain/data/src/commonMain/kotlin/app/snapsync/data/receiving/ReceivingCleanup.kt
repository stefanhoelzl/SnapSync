package app.snapsync.data.receiving

import app.snapsync.data.receiving.db.DownloadStoreQueries
import app.snapsync.domain.receiving.StagedPath

/** Receiving torn down, and staged files nothing names swept. */
class ReceivingCleanup(private val store: DownloadStore) {

    /**
     * Stop receiving: drop the transfer queue and cancel every transfer, then drop every row not yet received and free
     * its staged bytes — against ONE view of what is claimed, so the two halves cannot disagree. An import already
     * claimed is spared: its transaction may still commit, and its row stays a permanent suppression handle. Received
     * photos persist. Answers how many staged files it freed.
     */
    suspend fun stopReceiving(): DownloadRead<Int> = store.locked {
        store.transfers.cancelAll()
        store.read { q -> q.transactionWithResult { unsettledPruned(q) } }.then { stranded ->
            store.staging.release(stranded)
            DownloadRead.Ok(stranded.size)
        }
    }

    /**
     * Delete every staged file no resource row claims — bytes nothing will ever import or release, because every path
     * that frees staged bytes starts from a row (measured on the XS, 2026-10-06: 295 such files, 1.14 GB). A row
     * protects its path whether or not its download has landed, so a transfer finishing during the sweep keeps its
     * bytes. Under the lock, so no plan or prune moves the rows between the two reads. Answers how many it freed.
     */
    suspend fun sweepUnclaimed(): DownloadRead<Int> = store.locked {
        store.read(::pathsInUse).then { inUse -> swept(store.staging.listed(), inUse) }
    }

    private suspend fun swept(listed: Listed, inUse: Set<StagedPath>): DownloadRead<Int> = when (listed) {
        is Listed.Files -> listed.paths.filterNot { it in inUse }.let { unclaimed ->
            store.staging.release(unclaimed)
            DownloadRead.Ok(unclaimed.size)
        }
        is Listed.Unreadable -> DownloadRead.Unavailable(listed.detail)
    }

    /**
     * Read the rows not yet received, spare the claimed ones, drop the rest, and answer the staged paths they stranded.
     * The positions go with the plans (decision record `changes/incremental-union`, D6): one that stood past rows this
     * drops would keep the next delta from serving them again, so every next read is a whole one.
     */
    private fun unsettledPruned(q: DownloadStoreQueries): List<StagedPath> {
        q.deleteUnionCursors()
        q.selectPrunableAssets { device, asset -> device.held() to asset.held() }.executeAsList()
            .flatMap(::refsOf)
            .filterNot { it in store.claims }
            .forEach { q.deletePrunableAsset(valid(it.sourceDeviceId), valid(it.sourceAssetId)) }
        return stranded(q)
    }
}
