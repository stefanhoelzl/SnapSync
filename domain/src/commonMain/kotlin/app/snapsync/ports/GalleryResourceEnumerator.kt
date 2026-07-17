package app.snapsync.ports

import app.snapsync.model.Resource

/**
 * The library resource-enumeration seam: the single shared derivation of each resource's
 * `(filename, assetId)` (plus the platform `data`/`contentType` the producer needs to build
 * a job). Both the iOS background-upload producer's enumeration and the re-join seed go through this,
 * so an app-seeded key is byte-identical to what the producer later recomputes.
 *
 * The app (which never depends on the extension module) enumerates through this for the join;
 * PhotoKit-backed on iOS, a settable in-memory implementation for the JVM harness and tests. The
 * seam exposes resources, never owns sync decisions.
 */
interface GalleryResourceEnumerator {

    /**
     * Every resource of every asset captured at or after [since] — the full-enumeration set (the re-join
     * seed source), scoped by the membership's capture-date cutoff (capability `photo-selection-policy`). There
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
