package app.snapsync.gallery

import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.Resource
import app.snapsync.model.normalizeAssetId
import app.snapsync.ports.PhotoLibrary

/**
 * A settable in-memory [PhotoLibrary] for the JVM harness and tests: holds a fixed
 * resource list, re-emittable via [set]. [resources] filters by normalised `assetId` membership.
 * Fakes at the **post-mapping** `Resource` level — appropriate for consumers that test completeness
 * logic (e.g. `:domain:status`); to exercise the walk→map fan-out itself, use [InMemoryRawAssetSource].
 */
class InMemoryPhotoLibrary(initial: List<Resource> = emptyList()) : PhotoLibrary {

    private var all: List<Resource> = initial

    fun set(resources: List<Resource>) {
        all = resources
    }

    /** Mirrors the real walk's bound: resources of assets captured before [since] are not returned. */
    override suspend fun enumerate(since: String): List<Resource> =
        all.filter { (it.metadata[RESOURCE_META_CREATION_DATE] ?: "") >= since }

    override suspend fun resources(localIdentifiers: List<String>, since: String): List<Resource> {
        val wanted = localIdentifiers.mapTo(mutableSetOf()) { normalizeAssetId(it) }
        return all.filter { it.assetId in wanted && (it.metadata[RESOURCE_META_CREATION_DATE] ?: "") >= since }
    }
}
