package app.snapsync.gallery

import app.snapsync.engine.Resource

/**
 * The library resource-enumeration seam: the single shared derivation of each resource's
 * `(filename, assetId)` (plus the platform `data`/`contentType` the producer needs to build
 * a job). Both the iOS background-upload producer's enumeration and the re-join seed go through this,
 * so an app-seeded key is byte-identical to what the producer later recomputes.
 *
 * Lives in `:domain:gallery` so the app (which never depends on the extension module) can enumerate
 * for the join; PhotoKit-backed on iOS, a settable in-memory implementation for the JVM harness and
 * tests. The seam exposes resources, never owns sync decisions.
 */
interface GalleryResourceEnumerator {

    /**
     * Every resource of every asset captured at or after [since] — the full-enumeration set (the re-join
     * seed source), scoped by the membership's capture-date cutoff (capability `photo-date-cutoff`). There
     * is no unbounded variant: see [RawAssetSource.walkSince]. An implementation MAY return resources of
     * assets before [since]; the caller's own cutoff filter remains authoritative.
     */
    suspend fun enumerate(since: String): List<Resource>

    /**
     * The resources of the given asset local identifiers — the incremental set the producer uses for
     * changed assets — **bounded by [since]**, so a changed but out-of-scope asset never costs a resource
     * round-trip. Identifiers are the raw PhotoKit `localIdentifier`s (with `/`); the implementation
     * normalises them into the `<assetId>-…` key scheme.
     */
    suspend fun resources(localIdentifiers: List<String>, since: String): List<Resource>
}

/**
 * The **pure fan-out mapping** `RawAsset` → engine `Resource`s — the single site of the fan-out
 * orchestration, extracted from the iOS enumerator so it runs on JVM + the simulator (capability
 * `gallery-status`, Move A). For each [RawAsset]: normalize its `assetId` `'/'→'_'` ([normalizeAssetId]);
 * for each [RawResource], drop it when its raw [RawResource.type] maps to no role
 * ([resourceRole] — originals only), else wrap it as a `Resource` whose `filename` is the shared
 * [uploadKey] and whose `metadata` carries the per-asset manifest detail (creation date, original
 * filename, iOS-resolved MIME). The opaque [RawResource.handle] rides into `Resource.data` uninterpreted.
 * Platform-free, so the role-skip / normalization / key-derivation is exercised without PhotoKit.
 */
fun resourcesFrom(rawAssets: List<RawAsset>): List<Resource> =
    rawAssets.flatMap { asset ->
        val assetId = normalizeAssetId(asset.assetId)
        asset.rawResources.mapNotNull { raw ->
            val role = resourceRole(raw.type) ?: return@mapNotNull null
            Resource(
                filename = uploadKey(assetId, role, raw.originalFilename),
                assetId = assetId,
                contentType = raw.contentTypeUti,
                metadata = mapOf(
                    RESOURCE_META_CREATION_DATE to asset.creationDate,
                    RESOURCE_META_ORIGINAL_FILENAME to raw.originalFilename,
                    RESOURCE_META_MIME to raw.mimeContentType,
                ),
                data = raw.handle,
            )
        }
    }

/**
 * The [GalleryResourceEnumerator] as the composition of a decision-free [RawAssetSource] walk with the
 * pure [resourcesFrom] mapping. The iOS enumerator is `ResourceEnumerator(PhotoLibraryRawAssetSource())`;
 * a test drives it with an [InMemoryRawAssetSource]. This is where the walk (platform) and the mapping
 * (agnostic, tested) meet — the enumerator itself holds no decision.
 */
class ResourceEnumerator(private val source: RawAssetSource) : GalleryResourceEnumerator {
    override suspend fun enumerate(since: String): List<Resource> = resourcesFrom(source.walkSince(since))
    override suspend fun resources(localIdentifiers: List<String>, since: String): List<Resource> =
        resourcesFrom(source.walk(localIdentifiers, since))
}

/**
 * A settable in-memory [GalleryResourceEnumerator] for the JVM harness and tests: holds a fixed
 * resource list, re-emittable via [set]. [resources] filters by normalised `assetId` membership.
 * Fakes at the **post-mapping** `Resource` level — appropriate for consumers that test completeness
 * logic (e.g. `:domain:status`); to exercise the walk→map fan-out itself, use [InMemoryRawAssetSource].
 */
class InMemoryGalleryResourceEnumerator(initial: List<Resource> = emptyList()) : GalleryResourceEnumerator {

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
