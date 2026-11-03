package app.snapsync.data.receiving

import app.snapsync.data.receiving.db.DownloadStoreQueries
import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.event.EventId
import app.snapsync.domain.receiving.AdoptedPhoto
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.format.TimestampText

/** What a recognition pass writes: the photos an earlier install received, recorded as their refs' imports. */
class RecognitionRecords(private val store: DownloadStore) {

    /** The refs among [refs] that are settled here — imported, or judged unimportable. */
    fun settled(refs: Collection<AssetRef>): DownloadRead<Set<AssetRef>> = store.read { q -> settledAmong(q, refs) }

    /**
     * Record every [adopted] photo as its ref's confirmed import, and as [event]'s, in ONE transaction, and answer the
     * refs recorded. A ref with no row gets one; a ref planned or staged but not yet imported is settled onto the
     * adopted photo instead of being imported again. A ref this process has CLAIMED for an import is never adopted
     * underneath it: that import's own marker settles it.
     */
    suspend fun adopt(event: EventId, adopted: Collection<AdoptedPhoto>): DownloadRead<Set<AssetRef>> = store.locked {
        store.read { q ->
            q.transactionWithResult {
                adopted.filterNot { it.ref in store.claims }.filter {
                    adoptOne(
                        q,
                        event,
                        it,
                    )
                }.mapTo(mutableSetOf()) { it.ref }
            }
        }
    }

    /**
     * Forget which photos belonged to every event's union but [event]'s, and every other membership's recognition — a
     * join's housekeeping (change `event-scoped-local-state`): every read names its event, so another event's refs
     * count for nothing. The per-photo rows, and with them every import marker, are untouched.
     */
    fun forgetOtherEvents(event: EventId): StoreWrite = written(
        store.read { q ->
            q.transaction {
                q.purgeEventRefsExcept(valid(event))
                q.purgeRecognisedExcept(valid(event))
            }
        },
    )

    private fun adoptOne(q: DownloadStoreQueries, event: EventId, photo: AdoptedPhoto): Boolean =
        tagged(q, event, photo, adoptedNew(q, photo) || adoptedPlanned(q, photo))

    /** A ref with no row: recorded as imported onto the adopted photo. */
    private fun adoptedNew(q: DownloadStoreQueries, photo: AdoptedPhoto): Boolean = changed(q) {
        adoptImported(
            valid(photo.ref.sourceDeviceId),
            valid(photo.ref.sourceAssetId),
            TimestampText.captureText(photo.captured),
            valid(photo.localId),
        )
    }

    /** A ref planned or staged but not yet imported — no marker, not settled: settled onto the adopted photo. */
    private fun adoptedPlanned(q: DownloadStoreQueries, photo: AdoptedPhoto): Boolean = changed(q) {
        adoptPending(valid(photo.localId), valid(photo.ref.sourceDeviceId), valid(photo.ref.sourceAssetId))
    }

    /** A recorded adoption is [event]'s photo too. */
    private fun tagged(q: DownloadStoreQueries, event: EventId, photo: AdoptedPhoto, recorded: Boolean): Boolean {
        recorded.takeIf { it }?.let {
            q.insertEventRef(valid(event), valid(photo.ref.sourceDeviceId), valid(photo.ref.sourceAssetId))
        }
        return recorded
    }
}

private fun written(read: DownloadRead<Unit>): StoreWrite = when (read) {
    is DownloadRead.Ok -> StoreWrite.Written
    is DownloadRead.Unavailable -> StoreWrite.Failed(read.detail)
}
