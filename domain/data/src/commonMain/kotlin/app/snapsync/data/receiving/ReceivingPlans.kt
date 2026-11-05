package app.snapsync.data.receiving

import app.snapsync.data.receiving.db.DownloadStoreQueries
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.DownloadState
import app.snapsync.domain.receiving.ForeignPhotos
import app.snapsync.domain.receiving.StagedPath
import app.snapsync.domain.receiving.UnionPage
import app.snapsync.domain.receiving.UnionPhoto
import app.snapsync.domain.receiving.UnionReadPlan
import app.snapsync.format.TimestampText

/** What the store plans of an event's union: its open foreign photos, and the transfers that fetch them. */
class ReceivingPlans(private val store: DownloadStore) {

    /** The union position [event] was last read to and what the others declared then — `null`s for none. */
    fun position(event: EventId): DownloadRead<StoredPosition> = store.read { q ->
        StoredPosition(
            q.selectUnionCursor(valid(event)).executeAsOneOrNull(),
            q.selectUnionOthersDeclared(valid(event)).executeAsOneOrNull()?.othersDeclared,
        )
    }

    /**
     * Plan [page], read for [event] as [plan] says, for the device [me]: record its open foreign photos and their
     * resources, tag every foreign photo — settled ones included — as the event's, store the position the read covered
     * when it moved off [stored] in the SAME transaction, prune after a whole read, and start the transfers of what is
     * not yet staged. One transaction for the plan, where a transaction per photo paid a durable commit each (a
     * 101-photo backlog cost ~11.5 s on an iPhone XS).
     */
    suspend fun plan(event: EventId, page: UnionPage, plan: UnionReadPlan, me: DeviceId, stored: StoredPosition) =
        store.locked {
            store.read { q -> ForeignPhotos.of(page.photos, me, settledAmong(q, page.photos.map { it.ref })) }
                .then { foreign -> recorded(event, page, foreign, page.moved(stored.position, stored.othersDeclared)) }
                .then { foreign -> pruned(event, foreign, plan).then { enqueued(foreign.open.size, it) } }
        }

    private fun recorded(event: EventId, page: UnionPage, foreign: ForeignPhotos, moved: Boolean) =
        store.read { q -> q.transaction { record(q, event, page, foreign, moved) } }.then { DownloadRead.Ok(foreign) }

    private fun record(
        q: DownloadStoreQueries,
        event: EventId,
        page: UnionPage,
        foreign: ForeignPhotos,
        moved: Boolean,
    ) {
        foreign.open.forEach { recordPhoto(q, event, it) }
        foreign.refs.forEach { q.insertEventRef(valid(event), valid(it.sourceDeviceId), valid(it.sourceAssetId)) }
        recordPosition(q, event, page, moved)
    }

    /** Only when it moved: a wake that found nothing new costs no durable commit. */
    private fun recordPosition(q: DownloadStoreQueries, event: EventId, page: UnionPage, moved: Boolean) {
        page.position.takeIf { moved }?.let { q.upsertUnionCursor(valid(event), it, page.othersDeclared) }
    }

    private fun recordPhoto(q: DownloadStoreQueries, event: EventId, photo: UnionPhoto) {
        val (device, asset) = valid(photo.ref.sourceDeviceId) to valid(photo.ref.sourceAssetId)
        q.upsertAsset(device, asset, DownloadState.PENDING, TimestampText.captureText(photo.captured))
        photo.resources.forEach { r ->
            q.upsertResource(device, asset, r.key, r.url, r.role, r.contentType, r.originalFilename.value, event.value)
        }
    }

    /**
     * A whole read prunes (decision record `changes/incremental-union`, D7): [event]'s rows the union no longer lists
     * and this device has not received are dropped with their staged bytes — never settled, so a photo that comes back
     * is planned again — sparing every claimed import.
     */
    private fun pruned(event: EventId, foreign: ForeignPhotos, plan: UnionReadPlan): DownloadRead<List<StagedPath>> =
        when (plan) {
            UnionReadPlan.Whole -> store.read { q ->
                q.transactionWithResult {
                    withdrawn(
                        q,
                        event,
                        foreign.refs.toSet(),
                    )
                }
            }
            is UnionReadPlan.Since, UnionReadPlan.AlreadyRead -> DownloadRead.Ok(emptyList())
        }

    private fun withdrawn(q: DownloadStoreQueries, event: EventId, listed: Set<AssetRef>): List<StagedPath> {
        q.selectWithdrawable(valid(event)) { device, asset -> device.held() to asset.held() }.executeAsList()
            .flatMap(::refsOf)
            .filterNot { it in listed || it in store.claims }
            .forEach { q.deleteWithdrawn(valid(it.sourceDeviceId), valid(it.sourceAssetId)) }
        return stranded(q)
    }

    private suspend fun enqueued(planned: Int, stranded: List<StagedPath>): DownloadRead<Planned> {
        store.staging.release(stranded)
        return store.read(::pending).then { pending ->
            store.transfers.enqueue(pending)
            store.read { q -> q.transaction { pending.forEach { markEnqueued(q, it.tag) } } }
                .then { DownloadRead.Ok(Planned(planned, stranded.size, pending.size)) }
        }
    }
}

/** What the store kept of an event's last union read: the position, and what the others declared at it. */
class StoredPosition(val position: Long?, val othersDeclared: Long?)

/** What a plan did: [planned] open photos recorded, [pruned] staged files freed, [pending] resources handed on. */
class Planned(val planned: Int, val pruned: Int, val pending: Int)
