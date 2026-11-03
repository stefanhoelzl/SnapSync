package app.snapsync.data.receiving

import app.snapsync.data.receiving.db.SelectImportedHoldingBytes
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.receiving.AssetPresence
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.ImportedAsset
import app.snapsync.domain.receiving.InterruptedImportVerdict
import app.snapsync.domain.receiving.MintsImportedAsset
import app.snapsync.domain.receiving.UnconfirmedImport

/**
 * What follows an import: an imported photo's staged bytes released, and an import a dead process left unconfirmed
 * adjudicated — settled, cleared or left — through a write guarded on the marker it was decided for.
 */
class ImportedReleases(private val store: DownloadStore) {

    /** Every imported photo whose staged bytes are still recorded — what a release reclaims. */
    fun holdingBytes(): DownloadRead<List<ImportedAsset>> = store.read { q ->
        q.selectImportedHoldingBytes().executeAsList().flatMap(::importedOf)
    }

    /**
     * Free [imported]'s staged bytes and drop its resource rows, so the store never records a staged path for a file
     * that is gone — which makes a release self-extinguishing. Answers how many files it freed.
     */
    suspend fun release(imported: ImportedAsset): DownloadRead<Int> = store.locked { released(imported) }

    /** Rows whose asset was created but whose import was never confirmed — adjudicated, never imported again. */
    fun unconfirmed(): DownloadRead<List<UnconfirmedImport>> = store.read { q ->
        q.selectUnconfirmedAssets { device, asset, created -> Triple(device.held(), asset.held(), created.held()) }
            .executeAsList()
            .flatMap { (device, asset, created) ->
                refsOf(
                    device to asset,
                ).flatMap { ref -> created.map { UnconfirmedImport(ref, it) } }
            }
    }

    /**
     * Adjudicate [row] against what the library answered ([presence]), under the lock: whether its import runs
     * HERE now, and what its staged bytes say, are read with the decision, and the verdict is applied through a write
     * GUARDED on the marker it was computed for — the completion settles rows from the platform's queue holding no
     * lock, and an unguarded write to a row that moved on would strip or overwrite a live suppression handle.
     */
    suspend fun adjudicate(row: UnconfirmedImport, presence: AssetPresence): DownloadRead<Adjudicated> = store.locked {
        store.read { q -> stagedPaths(q, row.ref) }.then { paths ->
            val verdict = InterruptedImportVerdict.of(presence, row.ref in store.claims, store.staging.evidence(paths))
            applied(row, verdict).then { DownloadRead.Ok(Adjudicated(verdict, it)) }
        }
    }

    private suspend fun applied(row: UnconfirmedImport, verdict: InterruptedImportVerdict): DownloadRead<Boolean> =
        when (verdict) {
            InterruptedImportVerdict.Settle -> store.read { q -> confirm(q, row) }.then { settled(row, it) }
            InterruptedImportVerdict.Clear -> store.read { q -> clear(q, row) }
            InterruptedImportVerdict.Leave -> DownloadRead.Ok(true)
        }

    /** Only past the guard: releasing the bytes of a row that moved on would delete what a live import reads. */
    private suspend fun settled(row: UnconfirmedImport, applied: Boolean): DownloadRead<Boolean> = when (applied) {
        true -> released(importedAsset(row.ref, row.createdLocalId)).then { DownloadRead.Ok(true) }
            .also { store.claims.release(row.ref) }
        false -> DownloadRead.Ok(false)
    }

    private suspend fun released(imported: ImportedAsset): DownloadRead<Int> =
        store.read { q -> stagedPaths(q, imported.ref) }.then { paths ->
            store.staging.release(paths)
            store.read { q -> dropResources(q, imported.ref) }.then { DownloadRead.Ok(paths.size) }
        }
}

/** What adjudication did: the [verdict], and whether its guarded write [applied] (`false`: the row moved on). */
class Adjudicated(val verdict: InterruptedImportVerdict, val applied: Boolean)

private fun importedOf(row: SelectImportedHoldingBytes): List<ImportedAsset> =
    refsOf(row.sourceDeviceId.held() to row.sourceAssetId.held()).flatMap { ref ->
        row.createdLocalId.held().map { importedAsset(ref, it) }
    }

/**
 * The one mint of an [ImportedAsset]: a photo the store has recorded as imported onto [created] — by an import's own
 * write, by the read of an imported row, or by an interrupted import's guarded settle.
 */
@OptIn(MintsImportedAsset::class)
internal fun importedAsset(ref: AssetRef, created: AssetId): ImportedAsset = ImportedAsset(ref, created)
