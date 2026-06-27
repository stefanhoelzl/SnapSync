package app.snapsync.gallery

import app.snapsync.engine.Resource

/**
 * The library resource-enumeration seam: the single shared derivation of each resource's
 * `(filename, assetId, version)` (plus the platform `data`/`contentType` the producer needs to build
 * a job). Both the iOS background-upload producer's enumeration and the re-join seed go through this,
 * so an app-seeded key/version is byte-identical to what the producer later recomputes.
 *
 * Lives in `:domain:gallery` so the app (which never depends on the extension module) can enumerate
 * for the join; PhotoKit-backed on iOS, a settable in-memory implementation for the JVM harness and
 * tests. The seam exposes resources, never owns sync decisions.
 */
interface GalleryResourceEnumerator {

    /** Every resource in the whole library — the full-enumeration set (the re-join seed source). */
    suspend fun enumerate(): List<Resource>

    /**
     * The resources of the given asset local identifiers — the incremental set the producer uses for
     * changed assets. Identifiers are the raw PhotoKit `localIdentifier`s (with `/`); the
     * implementation normalises them into the `<assetId>-…` key scheme.
     */
    suspend fun resources(localIdentifiers: List<String>): List<Resource>
}

/**
 * A settable in-memory [GalleryResourceEnumerator] for the JVM harness and tests: holds a fixed
 * resource list, re-emittable via [set]. [resources] filters by normalised `assetId` membership.
 */
class InMemoryGalleryResourceEnumerator(initial: List<Resource> = emptyList()) : GalleryResourceEnumerator {

    private var all: List<Resource> = initial

    fun set(resources: List<Resource>) {
        all = resources
    }

    override suspend fun enumerate(): List<Resource> = all

    override suspend fun resources(localIdentifiers: List<String>): List<Resource> {
        val wanted = localIdentifiers.mapTo(mutableSetOf()) { it.replace('/', '_') }
        return all.filter { it.assetId in wanted }
    }
}
