package app.snapsync.data.receiving

import app.snapsync.data.receiving.db.DownloadStoreQueries
import app.snapsync.data.receiving.db.SelectPendingResources
import app.snapsync.data.store.Stored
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.StagedPath
import app.snapsync.domain.receiving.StagedResource
import app.snapsync.domain.receiving.StagingFolder

// The download store's row statements, as the classes over `DownloadStore` run them inside one `execute`.

/** [value] as the id column holds it. */
internal fun <T : Any> valid(value: T): Stored<T> = Stored.Valid(value)

/** The ref a row's two id columns spell, or none when either spells no id. */
internal fun refsOf(ids: Pair<List<DeviceId>, List<AssetId>>): List<AssetRef> =
    ids.first.flatMap { device -> ids.second.map { asset -> AssetRef(device, asset) } }

/**
 * The refs among [refs] settled here — imported, or judged unimportable. One read of every settled ref, filtered here:
 * the table holds only the unions of the events this device joined, and binding a list of key PAIRS is awkward.
 */
internal fun settledAmong(
    q: DownloadStoreQueries,
    refs: Collection<AssetRef>,
): Set<AssetRef> = refs.toSet().let { wanted ->
    q.selectSettledRefs { device, asset -> device.held() to asset.held() }.executeAsList()
        .flatMap(::refsOf)
        .filterTo(mutableSetOf()) { it in wanted }
}

/** Whether anything waits to download or import. */
internal fun waiting(q: DownloadStoreQueries): Boolean =
    q.selectPendingResources().executeAsList().isNotEmpty() || q.selectImportableAssets().executeAsList().isNotEmpty()

/** The staged resources of [ref], to hand to one library import. */
internal fun stagedResources(q: DownloadStoreQueries, ref: AssetRef): List<StagedResource> =
    q.selectResourcesForAsset(valid(ref.sourceDeviceId), valid(ref.sourceAssetId)).executeAsList().mapNotNull { row ->
        row.stagedPath?.let {
            StagedResource(
                row.resourceKey,
                row.role,
                row.contentType,
                row.originalFilename,
                StagedPath(it),
            )
        }
    }

/** The staged paths [ref]'s resource rows record. */
internal fun stagedPaths(q: DownloadStoreQueries, ref: AssetRef): List<StagedPath> =
    stagedResources(q, ref).map { it.stagedPath }

/** Drop [ref]'s resource rows — its bytes released. */
internal fun dropResources(q: DownloadStoreQueries, ref: AssetRef) {
    q.deleteResourcesForAsset(valid(ref.sourceDeviceId), valid(ref.sourceAssetId))
}

/** The not-yet-staged resources of every photo not settled — the download work queue. */
internal fun pending(q: DownloadStoreQueries): List<PendingResource> =
    q.selectPendingResources().executeAsList().flatMap(::pendingOf)

private fun pendingOf(row: SelectPendingResources): List<PendingResource> =
    refsOf(row.sourceDeviceId.held() to row.sourceAssetId.held()).map { ref ->
        PendingResource(TransferTag(ref, row.resourceKey, StagingFolder.of(row.eventId)), row.url)
    }

/** Mark [tag]'s resource as sent to the platform — the in-flight marker the status line's arrow pulses on. */
internal fun markEnqueued(q: DownloadStoreQueries, tag: TransferTag) {
    q.markResourceEnqueued(valid(tag.ref.sourceDeviceId), valid(tag.ref.sourceAssetId), tag.resourceKey)
}
