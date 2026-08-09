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

    override suspend fun isImported(ref: AssetRef): Boolean =
        q.isImported(ref.sourceDeviceId, ref.sourceAssetId).executeAsOne()

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
     */
    override fun recordCreatedLocalId(ref: AssetRef, createdLocalId: String) {
        q.recordCreatedLocalId(createdLocalId, ref.sourceDeviceId, ref.sourceAssetId)
    }

    /** The mirror of [recordCreatedLocalId], for a change the library reported as failed. */
    override fun clearCreatedLocalId(ref: AssetRef) {
        q.clearCreatedLocalId(ref.sourceDeviceId, ref.sourceAssetId)
    }

    /**
     * The success mirror. The marker guard is in the SQL, so a completion whose marker has moved on
     * updates no row rather than settling one it no longer describes.
     */
    override fun confirmCreatedLocalId(ref: AssetRef, createdLocalId: String) {
        q.confirmCreatedLocalId(ref.sourceDeviceId, ref.sourceAssetId, createdLocalId)
    }

    override suspend fun isUnconfirmedWith(ref: AssetRef, createdLocalId: String): Boolean =
        q.isUnconfirmedWith(ref.sourceDeviceId, ref.sourceAssetId, createdLocalId).executeAsOne()

    override suspend fun importedCount(): Int = q.countImported().executeAsOne().toInt()

    override suspend fun assetCount(): Int = q.countAssets().executeAsOne().toInt()

    override suspend fun inFlightCount(): Int = q.countInFlightAssets().executeAsOne().toInt()

    override suspend fun pruneNonTerminal() {
        q.transaction {
            q.deleteNonTerminalAssets()
            q.deleteNonTerminalResources()
        }
    }

    override suspend fun stagedPathsOfImportedAssets(): List<String> =
        q.selectStagedPathsOfImportedAssets().executeAsList().filterNotNull()

    override suspend fun stagedPathsOfPrunableAssets(): List<String> =
        q.selectStagedPathsOfPrunableAssets().executeAsList().filterNotNull()

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
