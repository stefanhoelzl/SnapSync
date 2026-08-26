package app.snapsync.downloadstore

import app.snapsync.ports.AssetRef
import app.snapsync.ports.DownloadState
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.ImportableAsset
import app.snapsync.ports.PendingDownload
import app.snapsync.ports.PlannedResource
import app.snapsync.ports.StagedResource
import app.snapsync.ports.UnconfirmedImport

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.snapsync.downloadstore.db.DownloadAsset
import app.snapsync.downloadstore.db.DownloadDatabase

/** [DownloadStore] over the SQLDelight [DownloadDatabase]. App-written; the extension opens it read-only. */
class SqlDelightDownloadStore(database: DownloadDatabase) : DownloadStore {

    private val q = database.downloadStoreQueries

    override suspend fun suppressedLocalIds(): Set<String> =
        q.suppressedLocalIds().executeAsList().mapNotNull { it }.toSet()

    override suspend fun isSettled(ref: AssetRef): Boolean =
        q.isSettled(ref.sourceDeviceId, ref.sourceAssetId).executeAsOne()

    override suspend fun plan(ref: AssetRef, creationDate: String, resources: List<PlannedResource>) {
        q.transaction {
            q.upsertAsset(ref.sourceDeviceId, ref.sourceAssetId, DownloadState.PENDING, creationDate)
            resources.forEach { r ->
                q.upsertResource(
                    ref.sourceDeviceId, ref.sourceAssetId, r.resourceKey,
                    r.url, r.role, r.contentType, r.originalFilename,
                )
            }
        }
    }

    override suspend fun pendingDownloads(): List<PendingDownload> =
        q.selectPendingResources { device, asset, key, url, role, contentType, original ->
            PendingDownload(AssetRef(device, asset), PlannedResource(key, url, role, contentType, original))
        }.executeAsList()

    override suspend fun markEnqueued(ref: AssetRef, resourceKey: String) {
        q.markResourceEnqueued(ref.sourceDeviceId, ref.sourceAssetId, resourceKey)
    }

    override suspend fun markStaged(ref: AssetRef, resourceKey: String, stagedPath: String) {
        q.markResourceStaged(stagedPath, ref.sourceDeviceId, ref.sourceAssetId, resourceKey)
    }

    override suspend fun importableAssets(): List<ImportableAsset> =
        q.selectImportableAssets { device, asset, creationDate ->
            ImportableAsset(AssetRef(device, asset), creationDate)
        }.executeAsList()

    override suspend fun unconfirmedImports(): List<UnconfirmedImport> =
        // The marker is non-null by the query's own `IS NOT NULL`, but the generated column type is
        // nullable, and the row mapper may not return null — so the mapper stays total and the narrowing
        // happens here, without an assertion that would outlive the query it depends on.
        q.selectUnconfirmedAssets { device, asset, createdLocalId ->
            AssetRef(device, asset) to createdLocalId
        }.executeAsList().mapNotNull { (ref, id) -> id?.let { UnconfirmedImport(ref, it) } }

    override suspend fun stagedResources(ref: AssetRef): List<StagedResource> =
        q.selectResourcesForAsset(ref.sourceDeviceId, ref.sourceAssetId) { key, _, role, contentType, original, staged ->
            StagedResource(key, role, contentType, original, staged ?: "")
        }.executeAsList().filter { it.stagedPath.isNotEmpty() }

    override suspend fun markImported(ref: AssetRef, createdLocalId: String) {
        q.markImported(createdLocalId, ref.sourceDeviceId, ref.sourceAssetId)
    }

    /**
     * Synchronous (non-suspend) write of ONLY the created local id — callable from inside a PhotoKit
     * `performChanges` change block (which cannot call suspend funcs) so the asset is suppressed before
     * its creation commits. The native SQLite write is synchronous; the later [markImported] sets state.
     *
     * `false` means the row was pruned out from under this import — see the port's KDoc for why that is
     * an emergency rather than a miss.
     */
    override fun recordCreatedLocalId(ref: AssetRef, createdLocalId: String): Boolean = applied {
        q.recordCreatedLocalId(createdLocalId, ref.sourceDeviceId, ref.sourceAssetId)
    }

    /** The mirror of [recordCreatedLocalId], for a change the library reported as failed. */
    override fun clearCreatedLocalId(ref: AssetRef, createdLocalId: String): Boolean = applied {
        q.clearCreatedLocalId(ref.sourceDeviceId, ref.sourceAssetId, createdLocalId)
    }

    /**
     * The success mirror. The marker guard is in the SQL, so a completion whose marker has moved on
     * updates no row rather than settling one it no longer describes.
     */
    override fun confirmCreatedLocalId(ref: AssetRef, createdLocalId: String): Boolean = applied {
        q.confirmCreatedLocalId(ref.sourceDeviceId, ref.sourceAssetId, createdLocalId)
    }

    /**
     * Run [write] and report whether it changed a row, reading `changes()` inside the SAME transaction as
     * the statement it describes — so nothing can run between the write and the question about it.
     *
     * Non-suspending, because all three of its callers are: they are invoked from PhotoKit's change and
     * completion blocks, which cannot call a suspending function.
     */
    private fun applied(write: () -> Unit): Boolean = q.transactionWithResult {
        write()
        q.changedRows().executeAsOne() > 0L
    }

    /**
     * Settle a row as permanently unimportable, and drop the resource rows that made it findable — one
     * transaction, so a reader can never see a settled row that still advertises staged paths for files the
     * library has already taken.
     *
     * `suspend` unlike the three marker writes: this one is reached from the drain, not from inside a
     * PhotoKit block, so it has no reason to carry their constraint.
     */
    override suspend fun settleUnimportable(ref: AssetRef): Boolean = q.transactionWithResult {
        q.settleUnimportable(ref.sourceDeviceId, ref.sourceAssetId)
        val applied = q.changedRows().executeAsOne() > 0L
        if (applied) q.deleteResourcesForAsset(ref.sourceDeviceId, ref.sourceAssetId)
        applied
    }

    override suspend fun importedCount(): Int = q.countImported().executeAsOne().toInt()

    override suspend fun assetCount(): Int = q.countAssets().executeAsOne().toInt()

    override suspend fun inFlightCount(): Int = q.countInFlightAssets().executeAsOne().toInt()

    /**
     * Read the prunable rows, subtract [protecting], drop what remains and return the staged paths those
     * rows owned — all inside ONE transaction, so no writer can move a row between the read that decided
     * its fate and the delete that carries it out.
     *
     * The refs are subtracted here rather than in SQL because the key is composite and a row-value `NOT IN`
     * over a bound collection is not expressible in this dialect. Being inside the transaction is what makes
     * that equivalent — and clearer to read than the alternative would have been.
     */
    override suspend fun pruneNonTerminal(protecting: Set<AssetRef>): List<String> = q.transactionWithResult {
        val victims = q.selectPrunableAssets { device, asset -> AssetRef(device, asset) }
            .executeAsList()
            .filterNot { it in protecting }
        victims.forEach { q.deletePrunableAsset(it.sourceDeviceId, it.sourceAssetId) }
        // Read AFTER the deletes, from the rows they orphaned — so the paths returned are what this call
        // really stranded, not what a pre-delete snapshot predicted it would. Getting that backwards frees
        // the bytes of a row the delete spared, and a resource recorded as staged is never re-downloaded.
        val stranded = q.selectStagedPathsOfOrphanedResources().executeAsList().filterNotNull()
        // Sweeps those now-orphaned resource rows; a protected asset keeps both its row and its resources.
        q.deleteNonTerminalResources()
        stranded
    }

    override suspend fun stagedPathsOfImportedAssets(): List<String> =
        q.selectStagedPathsOfImportedAssets().executeAsList().filterNotNull()

    override suspend fun dropResources(ref: AssetRef) {
        q.deleteResourcesForAsset(ref.sourceDeviceId, ref.sourceAssetId)
    }

    override suspend fun dropResourcesOfImportedAssets() {
        q.deleteResourcesOfImportedAssets()
    }
}

/** Construct the generated database with the [DownloadState] enum adapter wired (the single site that knows the encoding). */
fun DownloadDatabase(driver: SqlDriver): DownloadDatabase = DownloadDatabase(
    driver,
    DownloadAsset.Adapter(stateAdapter = EnumColumnAdapter()),
)
