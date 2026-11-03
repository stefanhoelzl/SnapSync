package app.snapsync.data.receiving

import app.snapsync.data.receiving.db.DownloadStoreQueries
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.ImportedAsset
import app.snapsync.domain.receiving.MintsStagedDownload
import app.snapsync.domain.receiving.StagedDownload
import app.snapsync.domain.receiving.StagedPath
import app.snapsync.format.TimestampText

/**
 * What arrives and goes into the library: a resource staged, a transfer ended, the next fully staged photo claimed, and
 * what its import came to. The only place a [StagedDownload] comes from; an [ImportedAsset] is minted by
 * [importedAsset].
 */
class ImportQueue(private val store: DownloadStore) {

    /**
     * A resource's bytes are staged at [at]: record it, and answer whether a row took it. A staging no row takes — the
     * transfer outran a leave's prune, or a relaunched process inherited it for an event it left — has its file
     * discarded here: nothing references it, and it would sit unreclaimable.
     */
    suspend fun staged(ref: AssetRef, resourceKey: String, at: StagedPath): DownloadRead<StageRecord> = store.locked {
        store.read { q ->
            markedStaged(
                q,
                ref,
                resourceKey,
                at,
            )
        }.then { DownloadRead.Ok(discarded(StageRecord.of(it), at)) }
    }

    /** [tag]'s transfer ended: its slot is free, and the next queued transfer starts. */
    suspend fun transferEnded(tag: String) = store.locked { store.transfers.freed(tag) }

    /**
     * The next photo whose every resource is staged and whose import is not claimed — neither in [skip] (attempted in
     * this pass) nor running here — CLAIMED, and everything its import needs read, all under the lock: the library call
     * is then the only thing outside it. `null` when nothing is left.
     */
    suspend fun claimNext(skip: Set<AssetRef>): DownloadRead<StagedDownload?> = store.locked {
        store.read { q ->
            importable(q).firstOrNull { (ref, _) -> ref !in skip && ref !in store.claims }?.let {
                claimed(q, it)
            }
        }
    }

    /**
     * [claim] was imported as [created]: the row is imported and [created] its suppression handle, then the claim is
     * released, in the same acquisition. Unguarded on the marker, and safe because the ref is STILL claimed: no second
     * import can have run for it. It is also the safety net for an importer that skipped its in-block marker write.
     */
    suspend fun imported(claim: StagedDownload, created: AssetId): DownloadRead<ImportedAsset> = store.locked {
        store.read { q ->
            q.markImported(
                valid(created),
                valid(claim.ref.sourceDeviceId),
                valid(claim.ref.sourceAssetId),
            )
        }
            .then { DownloadRead.Ok(importedAsset(claim.ref, created)) }
            .also { store.claims.release(claim.ref) }
    }

    /**
     * [claim]'s import failed and the library took its bytes: the row settles as unimportable, its resources with it,
     * and the claim is released. Answers whether the guarded write applied: `false` is a row that moved on while the
     * import ran, whose state must not be overwritten.
     */
    suspend fun unimportable(claim: StagedDownload): DownloadRead<Boolean> = store.locked {
        store.read { q -> q.transactionWithResult { settledUnimportable(q, claim.ref) } }
            .also { store.claims.release(claim.ref) }
    }

    /** [claim]'s import failed and left its bytes: the claim is released, and a later pass imports it again. */
    suspend fun deferred(claim: StagedDownload) = store.locked { store.claims.release(claim.ref) }

    private suspend fun discarded(record: StageRecord, at: StagedPath): StageRecord = record.also {
        when (record) {
            StageRecord.Recorded -> Unit
            StageRecord.NoRow -> store.staging.release(listOf(at))
        }
    }

    @OptIn(MintsStagedDownload::class)
    private fun claimed(q: DownloadStoreQueries, next: Pair<AssetRef, String>): StagedDownload =
        StagedDownload(next.first, TimestampText.captureDate(next.second), stagedResources(q, next.first))
            .also(store.claims::claim)
}

/** Whether a staging found its row. */
sealed interface StageRecord {
    data object Recorded : StageRecord

    /** No row took it, and its file was discarded. */
    data object NoRow : StageRecord

    companion object {
        fun of(took: Boolean): StageRecord = when (took) {
            true -> Recorded
            false -> NoRow
        }
    }
}
