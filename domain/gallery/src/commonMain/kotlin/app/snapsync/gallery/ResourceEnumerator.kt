package app.snapsync.gallery

import app.snapsync.model.Resource
import app.snapsync.model.resourcesFrom
import app.snapsync.ports.GalleryResourceEnumerator
import app.snapsync.ports.RawAssetSource

/**
 * The [GalleryResourceEnumerator] as the composition of a decision-free [RawAssetSource] walk with the
 * pure [resourcesFrom] mapping. The iOS enumerator is `ResourceEnumerator(PhotoLibraryRawAssetSource())`;
 * a test drives it with an `InMemoryRawAssetSource`. This is where the walk (platform) and the mapping
 * (agnostic, tested) meet — the enumerator itself holds no decision.
 */
class ResourceEnumerator(private val source: RawAssetSource) : GalleryResourceEnumerator {
    override suspend fun enumerate(since: String): List<Resource> = resourcesFrom(source.walkSince(since))
    override suspend fun resources(localIdentifiers: List<String>, since: String): List<Resource> =
        resourcesFrom(source.walk(localIdentifiers, since))
}
