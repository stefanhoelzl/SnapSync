package app.snapsync.fake

import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.Resource
import app.snapsync.model.normalizeAssetId
import app.snapsync.ports.PhotoLibrary
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An honest in-memory [PhotoLibrary]: reads a constructor-injected state cell, so whoever owns the
 * cell (a test, a `:test:world` wrapper) sets its contents — the fake itself exposes only the port
 * (the honesty gate). [resources] filters by normalised `assetId` membership. Fakes at the
 * **post-mapping** [Resource] level — appropriate for consumers that test completeness logic (e.g.
 * `:domain`'s feature/status); to exercise the walk→map fan-out itself, use [InMemoryRawAssetSource].
 */
class InMemoryPhotoLibrary(private val state: MutableStateFlow<List<Resource>>) : PhotoLibrary {

    constructor(initial: List<Resource> = emptyList()) : this(MutableStateFlow(initial))

    /** Mirrors the real walk's bound: resources of assets captured before [since] are not returned. */
    override suspend fun enumerate(since: String): List<Resource> =
        state.value.filter { (it.metadata[RESOURCE_META_CREATION_DATE] ?: "") >= since }

    override suspend fun resources(localIdentifiers: List<String>, since: String): List<Resource> {
        val wanted = localIdentifiers.mapTo(mutableSetOf()) { normalizeAssetId(it) }
        return state.value.filter { it.assetId in wanted && (it.metadata[RESOURCE_META_CREATION_DATE] ?: "") >= since }
    }
}
