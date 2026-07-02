package app.snapsync.downloadstore

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A settable in-memory [DownloadStore] for the desktop harness and tests (the iOS app/extension back
 * it with the SQLDelight store). Holds the same semantics: idempotency by [AssetRef], staged-path per
 * resource, permanent IMPORTED rows.
 */
class InMemoryDownloadStore : DownloadStore {

    private class AssetRow(var state: DownloadState, val creationDate: String, var createdLocalId: String?)

    private val lock = Mutex()
    private val assets = LinkedHashMap<AssetRef, AssetRow>()
    private val resources = LinkedHashMap<AssetRef, MutableMap<String, Pair<PlannedResource, String?>>>()

    override suspend fun suppressedLocalIds(): Set<String> = lock.withLock {
        assets.values.mapNotNull { it.createdLocalId }.toSet()
    }

    override suspend fun isImported(ref: AssetRef): Boolean = lock.withLock {
        assets[ref]?.state == DownloadState.IMPORTED
    }

    override suspend fun plan(ref: AssetRef, creationDate: String, resources: List<PlannedResource>) = lock.withLock {
        if (assets[ref]?.state == DownloadState.IMPORTED) return@withLock
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
                if (assets[ref]?.state == DownloadState.IMPORTED) return@forEach
                byKey.values.forEach { (planned, staged) ->
                    if (staged == null) add(PendingDownload(ref, planned))
                }
            }
        }
    }

    override suspend fun markStaged(ref: AssetRef, resourceKey: String, stagedPath: String) = lock.withLock {
        val byKey = resources[ref] ?: return@withLock
        val planned = byKey[resourceKey]?.first ?: return@withLock
        byKey[resourceKey] = planned to stagedPath
    }

    override suspend fun importableAssets(): List<ImportableAsset> = lock.withLock {
        assets.filter { (ref, row) ->
            row.state != DownloadState.IMPORTED &&
                resources[ref]?.isNotEmpty() == true &&
                resources[ref]!!.values.all { it.second != null }
        }.map { (ref, row) -> ImportableAsset(ref, row.creationDate) }
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

    override suspend fun importedCount(): Int = lock.withLock {
        assets.values.count { it.state == DownloadState.IMPORTED }
    }

    override suspend fun assetCount(): Int = lock.withLock { assets.size }

    override suspend fun pruneNonTerminal() = lock.withLock {
        val drop = assets.filter { it.value.state != DownloadState.IMPORTED }.keys.toList()
        drop.forEach { assets.remove(it); resources.remove(it) }
    }
}
