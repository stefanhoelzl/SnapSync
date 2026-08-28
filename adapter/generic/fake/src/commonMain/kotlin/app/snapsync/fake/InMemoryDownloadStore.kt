package app.snapsync.fake

import app.snapsync.ports.AssetRef
import app.snapsync.ports.DownloadState
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.ImportableAsset
import app.snapsync.ports.PendingDownload
import app.snapsync.ports.PlannedResource
import app.snapsync.ports.StagedResource
import app.snapsync.ports.UnconfirmedImport

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The honest in-memory [DownloadStore] for the world harness and tests (the iOS app/extension back
 * it with the SQLDelight store). Holds the same semantics: idempotency by [AssetRef], staged-path per
 * resource, permanent IMPORTED rows.
 */
internal class InMemoryDownloadStore : DownloadStore {

    private class AssetRow(var state: DownloadState, val creationDate: String, var createdLocalId: String?)

    private val lock = Mutex()
    private val assets = LinkedHashMap<AssetRef, AssetRow>()
    private val resources = LinkedHashMap<AssetRef, MutableMap<String, Pair<PlannedResource, String?>>>()

    // Resource keys whose download has been sent to the OS (the enqueued marker) — combined with a
    // null staged path this is "in flight" (the ↓-pulse signal).
    private val enqueued = LinkedHashMap<AssetRef, MutableSet<String>>()

    override suspend fun suppressedLocalIds(): Set<String> = lock.withLock {
        assets.values.mapNotNull { it.createdLocalId }.toSet()
    }

    override suspend fun isSettled(ref: AssetRef): Boolean = lock.withLock {
        assets[ref]?.state?.isTerminal == true
    }

    override suspend fun plan(ref: AssetRef, creationDate: String, resources: List<PlannedResource>) = lock.withLock {
        if (assets[ref]?.state?.isTerminal == true) return@withLock
        assets.getOrPut(ref) { AssetRow(DownloadState.PENDING, creationDate, null) }
        val byKey = this.resources.getOrPut(ref) { linkedMapOf() }
        // Refresh the planned resource (its `url`) for new AND not-yet-staged rows so a freshly
        // presigned download URL supersedes an expiring one; leave a staged row untouched.
        resources.forEach { r ->
            val existing = byKey[r.resourceKey]
            if (existing == null || existing.second == null) byKey[r.resourceKey] = r to null
        }
    }

    override suspend fun pendingDownloads(): List<PendingDownload> = lock.withLock {
        buildList {
            resources.forEach { (ref, byKey) ->
                if (assets[ref]?.state?.isTerminal == true) return@forEach
                byKey.values.forEach { (planned, staged) ->
                    if (staged == null) add(PendingDownload(ref, planned))
                }
            }
        }
    }

    override suspend fun markEnqueued(ref: AssetRef, resourceKey: String) = lock.withLock {
        enqueued.getOrPut(ref) { linkedSetOf() }.add(resourceKey)
        Unit
    }

    override suspend fun markStaged(ref: AssetRef, resourceKey: String, stagedPath: String) = lock.withLock {
        val byKey = resources[ref] ?: return@withLock
        val planned = byKey[resourceKey]?.first ?: return@withLock
        byKey[resourceKey] = planned to stagedPath
    }

    override suspend fun importableAssets(): List<ImportableAsset> = lock.withLock {
        assets.filter { (ref, row) ->
            !row.state.isTerminal &&
                // A row carrying a marker already has an asset in the library: adjudicated, not imported.
                row.createdLocalId == null &&
                resources[ref]?.isNotEmpty() == true &&
                resources[ref]!!.values.all { it.second != null }
        }.map { (ref, row) -> ImportableAsset(ref, row.creationDate) }
    }

    override suspend fun unconfirmedImports(): List<UnconfirmedImport> = lock.withLock {
        assets.mapNotNull { (ref, row) ->
            row.createdLocalId
                ?.takeIf { !row.state.isTerminal }
                ?.let { UnconfirmedImport(ref, it) }
        }
    }

    override suspend fun stagedResources(ref: AssetRef): List<StagedResource> = lock.withLock {
        resources[ref].orEmpty().values.mapNotNull { (planned, staged) ->
            staged?.let {
                StagedResource(planned.resourceKey, planned.role, planned.contentType, planned.originalFilename, it)
            }
        }
    }

    override suspend fun markImported(ref: AssetRef, createdLocalId: String) = lock.withLock {
        assets[ref]?.let { it.state = DownloadState.IMPORTED; it.createdLocalId = createdLocalId }
        Unit
    }

    /**
     * Lock-free on purpose, mirroring the real store: the port declares this non-`suspend` because the
     * platform's change block cannot suspend, so it cannot take [lock] either. The real impl is a single
     * synchronous SQLite write; this is a single field write, and the fake's consumers are tests driving
     * one dispatcher.
     */
    override fun recordCreatedLocalId(ref: AssetRef, createdLocalId: String): Boolean {
        val row = assets[ref] ?: return false // the row was pruned out from under this import
        row.createdLocalId = createdLocalId
        return true
    }

    /**
     * The mirror of [recordCreatedLocalId], lock-free for the same reason. Guarded on the marker AND on the
     * row still being non-terminal, exactly like the real store's `WHERE` clause: a clear arriving after
     * the row settled strips the suppression handle off an asset that exists, permanently.
     */
    override fun clearCreatedLocalId(ref: AssetRef, createdLocalId: String): Boolean {
        val row = assets[ref] ?: return false
        if (row.state.isTerminal || row.createdLocalId != createdLocalId) return false
        row.createdLocalId = null
        return true
    }

    /**
     * The success mirror, lock-free for the same reason. Guarded on the marker exactly like the real
     * store's `WHERE` clause: a completion arriving after the row's marker moved on settles nothing.
     */
    override fun confirmCreatedLocalId(ref: AssetRef, createdLocalId: String): Boolean {
        val row = assets[ref] ?: return false
        if (row.createdLocalId != createdLocalId) return false
        row.state = DownloadState.IMPORTED
        return true
    }

    override suspend fun importedCount(): Int = lock.withLock {
        assets.values.count { it.state == DownloadState.IMPORTED }
    }

    // An `UNIMPORTABLE` asset leaves the denominator (design D8): it can never arrive, so counting it
    // pegs the download line below completion forever on work that will never finish.
    override suspend fun assetCount(): Int = lock.withLock {
        assets.values.count { it.state != DownloadState.UNIMPORTABLE }
    }

    override suspend fun inFlightCount(): Int = lock.withLock {
        assets.count { (ref, row) ->
            !row.state.isTerminal &&
                enqueued[ref].orEmpty().any { key -> resources[ref]?.get(key)?.second == null }
        }
    }

    override suspend fun pruneNonTerminal(protecting: Set<AssetRef>): List<String> = lock.withLock {
        // A row carrying a marker is NEVER dropped: the marker is the only record that its asset must
        // not be uploaded, and deleting it is what sends someone else's photo back into the event.
        // Neither is a row whose import is in flight — its change block has not run, so it carries no
        // marker yet, and dropping it makes that marker write land on nothing.
        val drop = assets
            .filter { it.value.state != DownloadState.IMPORTED && it.value.createdLocalId == null }
            .keys
            .filterNot { it in protecting }
            .toList()
        // The paths come from the rows this call REALLY removes — the same ordering the real store gets by
        // reading orphaned resources after its deletes. Freeing the bytes of a row that was spared is how a
        // photo becomes permanently unimportable (a staged resource is never re-downloaded).
        val stranded = drop.flatMap { ref -> resources[ref].orEmpty().values.mapNotNull { it.second } }
        drop.forEach { assets.remove(it); resources.remove(it); enqueued.remove(it) }
        stranded
    }

    /**
     * Settle a row as permanently unimportable and drop the resource rows that made it findable, mirroring
     * the real store's single transaction. Guarded on the row still being non-terminal and carrying no
     * marker: this write's precondition is that no asset was created.
     */
    override suspend fun settleUnimportable(ref: AssetRef): Boolean = lock.withLock {
        val row = assets[ref] ?: return@withLock false
        if (row.state.isTerminal || row.createdLocalId != null) return@withLock false
        row.state = DownloadState.UNIMPORTABLE
        resources.remove(ref)
        enqueued.remove(ref)
        true
    }

    override suspend fun stagedPathsOfImportedAssets(): List<String> = lock.withLock {
        assets.filter { it.value.state == DownloadState.IMPORTED }
            .keys
            .flatMap { ref -> resources[ref].orEmpty().values.mapNotNull { it.second } }
    }

    override suspend fun dropResources(ref: AssetRef) = lock.withLock {
        resources.remove(ref)
        enqueued.remove(ref)
        Unit
    }

    override suspend fun dropResourcesOfImportedAssets() = lock.withLock {
        assets.filter { it.value.state == DownloadState.IMPORTED }.keys.forEach {
            resources.remove(it); enqueued.remove(it)
        }
    }
}
