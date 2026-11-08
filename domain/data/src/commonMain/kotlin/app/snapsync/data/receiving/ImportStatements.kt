package app.snapsync.data.receiving

import app.snapsync.data.receiving.DownloadDatabaseFile.Companion.held
import app.snapsync.data.receiving.MarkerStatements.changed
import app.snapsync.data.receiving.RowStatements.dropResources
import app.snapsync.data.receiving.RowStatements.refsOf
import app.snapsync.data.receiving.RowStatements.valid
import app.snapsync.data.receiving.db.DownloadStoreQueries
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.StagedPath

// The download store's statements of a photo on its way into the library.

object ImportStatements {
    /** Record [ref]'s [resourceKey] as staged at [at]; answers whether a row took it. */
    internal fun markedStaged(q: DownloadStoreQueries, ref: AssetRef, resourceKey: String, at: StagedPath): Boolean =
        q.markResourceStaged(at.value, valid(ref.sourceDeviceId), valid(ref.sourceAssetId), resourceKey).value > 0

    /** Every photo whose resources are all staged and that carries no marker, with its capture text. */
    internal fun importable(q: DownloadStoreQueries): List<Pair<AssetRef, String>> =
        q.selectImportableAssets { device, asset, created -> (device.held() to asset.held()) to created }
            .executeAsList()
            .flatMap { (ids, created) -> refsOf(ids).map { it to created } }

    /**
     * Settle [ref] as unimportable — guarded on the row still being unsettled and carrying no marker — and drop its
     * resource rows when it did, so no reader sees a settled row advertising files the library already took.
     */
    internal fun settledUnimportable(q: DownloadStoreQueries, ref: AssetRef): Boolean =
        changed(q) { settleUnimportable(valid(ref.sourceDeviceId), valid(ref.sourceAssetId)) }.also { applied ->
            applied.takeIf { it }?.let { dropResources(q, ref) }
        }
}
