package app.snapsync.data.receiving

import app.snapsync.data.receiving.db.DownloadStoreQueries
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.ImportedAsset
import app.snapsync.domain.receiving.MintsStagedDownload
import app.snapsync.domain.receiving.StagedDownload
import app.snapsync.domain.receiving.StagedPath
import app.snapsync.domain.receiving.StagedResource
import app.snapsync.format.TimestampText
import app.snapsync.inbound.Log
import app.snapsync.inbound.of

private val log = Log("ImportQueue")

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
     *
     * A photo whose stored resource names no sender filename is never claimed: it settles unimportable, its staged
     * bytes are released, and the next photo is taken (change `a-received-photo-always-has-a-sender-filename`).
     */
    suspend fun claimNext(skip: Set<AssetRef>): DownloadRead<StagedDownload?> = store.locked { nextClaimed(skip) }

    private suspend fun nextClaimed(skip: Set<AssetRef>): DownloadRead<StagedDownload?> =
        store.read { q -> nextImport(q, store.claims, skip) }.then { next -> answered(next, skip) }

    private suspend fun answered(next: NextImport?, skip: Set<AssetRef>): DownloadRead<StagedDownload?> = when (next) {
        null -> DownloadRead.Ok(null)
        is NextImport.Claimed -> DownloadRead.Ok(next.download)
        is NextImport.Unnamed -> refused(next, skip)
    }

    private suspend fun refused(next: NextImport.Unnamed, skip: Set<AssetRef>): DownloadRead<StagedDownload?> {
        store.staging.release(next.paths)
        log.w("a staged photo names no sender filename; it is never imported", "asset" of next.ref.sourceAssetId)
        return nextClaimed(skip + next.ref)
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
}

/** The next importable photo neither in [skip] nor in [claims]: claimed, or settled unimportable for naming nothing. */
private fun nextImport(q: DownloadStoreQueries, claims: ImportClaims, skip: Set<AssetRef>): NextImport? =
    importable(q).firstOrNull { (ref, _) -> ref !in skip && ref !in claims }?.let { taken(q, claims, it) }

private fun taken(q: DownloadStoreQueries, claims: ImportClaims, next: Pair<AssetRef, String>): NextImport =
    when (val rows = stagedRows(q, next.first)) {
        is StagedRows.Named -> NextImport.Claimed(claimed(claims, next, rows.resources))
        is StagedRows.Unnamed -> NextImport.Unnamed(next.first, rows.paths)
            .also { q.transactionWithResult { settledUnimportable(q, next.first) } }
    }

@OptIn(MintsStagedDownload::class)
private fun claimed(
    claims: ImportClaims,
    next: Pair<AssetRef, String>,
    resources: List<StagedResource>,
): StagedDownload = StagedDownload(next.first, TimestampText.captureDate(next.second), resources).also(claims::claim)

/** What [ImportQueue.claimNext] found next: a claimed photo, or one whose stored resource names no sender filename. */
private sealed interface NextImport {
    class Claimed(val download: StagedDownload) : NextImport

    /** Settled unimportable under the lock; its staged [paths] are still to release. */
    class Unnamed(val ref: AssetRef, val paths: List<StagedPath>) : NextImport
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
