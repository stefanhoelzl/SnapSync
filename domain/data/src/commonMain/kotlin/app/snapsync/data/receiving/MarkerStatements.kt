package app.snapsync.data.receiving

import app.snapsync.data.receiving.db.DownloadStoreQueries
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.UnconfirmedImport

// The guarded writes of an import's marker — the suppression handle — each reading whether it applied.

/**
 * Settle [row] as imported against the marker it ALREADY holds, never a fresh one; answers whether the guard matched.
 * The `createdLocalId` guard is what makes a late completion safe: a marker cleared and re-imported under a new one
 * matches no row.
 */
internal fun confirm(q: DownloadStoreQueries, row: UnconfirmedImport): Boolean = changed(q) {
    confirmCreatedLocalId(valid(row.ref.sourceDeviceId), valid(row.ref.sourceAssetId), valid(row.createdLocalId))
}

/** Clear [row]'s marker — guarded on the row being unsettled and still carrying it; answers whether it matched. */
internal fun clear(q: DownloadStoreQueries, row: UnconfirmedImport): Boolean = changed(q) {
    clearCreatedLocalId(valid(row.ref.sourceDeviceId), valid(row.ref.sourceAssetId), valid(row.createdLocalId))
}

/** Record [created] as [ref]'s marker, leaving it pending; answers whether a row took it. */
internal fun recordMarker(q: DownloadStoreQueries, ref: AssetRef, created: AssetId): Boolean = changed(q) {
    recordCreatedLocalId(valid(created), valid(ref.sourceDeviceId), valid(ref.sourceAssetId))
}

/**
 * Run [write] and answer whether it changed a row, reading `changes()` inside the SAME transaction — so nothing runs
 * between the write and the question about it.
 */
internal fun changed(
    q: DownloadStoreQueries,
    write: DownloadStoreQueries.() -> Unit,
): Boolean = q.transactionWithResult {
    q.write()
    q.changedRows().executeAsOne() > 0L
}
