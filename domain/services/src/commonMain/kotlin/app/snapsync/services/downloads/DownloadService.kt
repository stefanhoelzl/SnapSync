package app.snapsync.services.downloads

import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.DownloadCounts
import app.snapsync.model.DownloadState
import app.snapsync.ports.DownloadStore
import app.snapsync.model.ImportableAsset
import app.snapsync.model.PendingDownload
import app.snapsync.model.PlannedAsset
import app.snapsync.model.PlannedResource
import app.snapsync.model.StagedResource
import app.snapsync.model.UnconfirmedImport

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.snapsync.model.SuppressionReadiness
import app.snapsync.ports.Databases
import app.snapsync.services.databases.AssetIdColumnAdapter
import app.snapsync.services.databases.openOwned
import app.snapsync.services.downloads.db.DownloadAsset
import app.snapsync.services.downloads.db.DownloadDatabase
import app.snapsync.services.downloads.db.DownloadResource

/** The download store's database file — runtime identity (`docs/architecture.md`, section 9). */
const val DOWNLOADS_DB_NAME: String = "downloads.db"

/**
 * The download store (capability `receiving-photos`): [DownloadStore] over the SQLDelight [DownloadDatabase].
 * **The app is its one writer and the one process that migrates it**; the upload extension reads only its
 * suppression projection, read-only, through [SuppressionService].
 *
 * Opened on first use through [Databases], never at construction, and a failed open is retried on the next use
 * (see [app.snapsync.services.ledger.LedgerService]).
 */
class DownloadService(databases: Databases) : DownloadStore {

    private val q by lazy { DownloadDatabase(databases.openOwned(DOWNLOADS_DB_NAME, DownloadDatabase.Schema)).downloadStoreQueries }

    /**
     * Always ready: a read-write open creates and migrates, so this process can never find the store at an old
     * schema, and an open that fails surfaces from the read itself, as it always has.
     */
    override suspend fun readiness(): SuppressionReadiness = SuppressionReadiness.Ready

    override suspend fun suppressedLocalIds(): Set<AssetId> =
        q.suppressedLocalIds().executeAsList().mapNotNull { it }.toSet()

    // Reads every imported row and filters here: the table holds only the unions of the events this device
    // has joined, and binding a list of key PAIRS is awkward in SQLDelight. The port is stated by ref, so an
    // index-driven query can replace this without touching a caller.
    override suspend fun importedLocalIds(refs: Collection<AssetRef>): Map<AssetRef, AssetId> {
        if (refs.isEmpty()) return emptyMap()
        val wanted = refs.toSet()
        return q.selectImportedLocalIds { device, asset, localId -> AssetRef(device, asset) to localId }
            .executeAsList()
            .filter { (ref, _) -> ref in wanted }
            .toMap()
    }

    override suspend fun isSettled(ref: AssetRef): Boolean =
        q.isSettled(ref.sourceDeviceId, ref.sourceAssetId).executeAsOne()

    // One read of every settled ref, filtered here — the same trade as [importedLocalIds], for the same reason.
    override suspend fun settledAmong(refs: Collection<AssetRef>): Set<AssetRef> {
        if (refs.isEmpty()) return emptySet()
        val wanted = refs.toSet()
        return q.selectSettledRefs { device, asset -> AssetRef(device, asset) }
            .executeAsList()
            .filterTo(mutableSetOf()) { it in wanted }
    }

    override suspend fun plan(ref: AssetRef, creationDate: String, resources: List<PlannedResource>) =
        planAll(listOf(PlannedAsset(ref, creationDate, resources)))

    /**
     * Every asset and its resources inside ONE transaction — one durable commit for the batch, where a
     * transaction per asset paid one each. Per-asset atomicity is a consequence, not a trade: an asset's row
     * and its resources still land together, because the whole batch does.
     */
    override suspend fun planAll(assets: List<PlannedAsset>) {
        if (assets.isEmpty()) return
        q.transaction {
            assets.forEach { (ref, creationDate, resources) ->
                q.upsertAsset(ref.sourceDeviceId, ref.sourceAssetId, DownloadState.PENDING, creationDate)
                resources.forEach { r ->
                    q.upsertResource(
                        ref.sourceDeviceId, ref.sourceAssetId, r.resourceKey,
                        r.url, r.role, r.contentType, r.originalFilename,
                    )
                }
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

    /** Every mark in ONE transaction: one durable commit for the batch rather than an autocommit per resource. */
    override suspend fun markAllEnqueued(downloads: Collection<PendingDownload>) {
        if (downloads.isEmpty()) return
        q.transaction {
            downloads.forEach { q.markResourceEnqueued(it.ref.sourceDeviceId, it.ref.sourceAssetId, it.resource.resourceKey) }
        }
    }

    override suspend fun markStaged(ref: AssetRef, resourceKey: String, stagedPath: String): Boolean =
        q.markResourceStaged(stagedPath, ref.sourceDeviceId, ref.sourceAssetId, resourceKey).value > 0

    override suspend fun importableAssets(): List<ImportableAsset> =
        q.selectImportableAssets { device, asset, creationDate ->
            ImportableAsset(AssetRef(device, asset), creationDate)
        }.executeAsList()

    override suspend fun unconfirmedImports(): List<UnconfirmedImport> =
        // The marker is non-null by the query's own `IS NOT NULL`, and SQLDelight narrows the generated
        // column type from it — so no narrowing (and no assertion) is needed here.
        q.selectUnconfirmedAssets { device, asset, createdLocalId ->
            UnconfirmedImport(AssetRef(device, asset), createdLocalId)
        }.executeAsList()

    override suspend fun stagedResources(ref: AssetRef): List<StagedResource> =
        q.selectResourcesForAsset(ref.sourceDeviceId, ref.sourceAssetId) { key, _, role, contentType, original, staged ->
            StagedResource(key, role, contentType, original, staged ?: "")
        }.executeAsList().filter { it.stagedPath.isNotEmpty() }

    override suspend fun markImported(ref: AssetRef, createdLocalId: AssetId) {
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
    override fun recordCreatedLocalId(ref: AssetRef, createdLocalId: AssetId): Boolean = applied {
        q.recordCreatedLocalId(createdLocalId, ref.sourceDeviceId, ref.sourceAssetId)
    }

    /** The mirror of [recordCreatedLocalId], for a change the library reported as failed. */
    override fun clearCreatedLocalId(ref: AssetRef, createdLocalId: AssetId): Boolean = applied {
        q.clearCreatedLocalId(ref.sourceDeviceId, ref.sourceAssetId, createdLocalId)
    }

    /**
     * The success mirror. The marker guard is in the SQL, so a completion whose marker has moved on
     * updates no row rather than settling one it no longer describes.
     */
    override fun confirmCreatedLocalId(ref: AssetRef, createdLocalId: AssetId): Boolean = applied {
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

    /**
     * ONE statement, not three reads and not three reads inside a transaction: the counts are subqueries of a
     * single `SELECT`, which SQLite evaluates against one snapshot. A transaction wrapping three separate
     * reads would be equivalent here, but it would put the consistency in the caller's hands, where the next
     * count added could quietly be read outside it.
     */
    override suspend fun counts(): DownloadCounts = q.projectionCounts().executeAsOne().let {
        DownloadCounts(
            imported = it.imported.toInt(),
            stillArriving = it.stillArriving.toInt(),
            inFlight = it.inFlight.toInt(),
        )
    }

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
internal fun DownloadDatabase(driver: SqlDriver): DownloadDatabase = DownloadDatabase(
    driver,
    DownloadAsset.Adapter(
        sourceAssetIdAdapter = AssetIdColumnAdapter,
        stateAdapter = EnumColumnAdapter(),
        createdLocalIdAdapter = AssetIdColumnAdapter,
    ),
    DownloadResource.Adapter(sourceAssetIdAdapter = AssetIdColumnAdapter),
)
