package app.snapsync.ports

import app.snapsync.model.RawAsset

/**
 * The **decision-free** library walk seam: exposes PhotoKit as [RawAsset]s carrying only raw facts. The
 * iOS implementation is PhotoKit-backed; [InMemoryRawAssetSource] stands in on the JVM and simulator so
 * the [resourcesFrom] fan-out mapping is exercised off-device. The walk performs no role filter, key
 * derivation, or `assetId` normalization — every original and non-original resource crosses as a fact.
 *
 * There is **no unbounded walk**: [walkSince] takes a capture-date lower bound, because walking the whole
 * library costs one synchronous PhotoKit round-trip per asset and a membership always has a cutoff to
 * scope it (capability `photo-selection-policy`). The bound is a **scope parameter, not a decision** — what the
 * bound *is* remains a `commonMain` choice; the walk merely receives it, and the caller's own filter stays
 * authoritative over whatever comes back.
 */
interface RawAssetSource {
    /**
     * Walk every asset whose `creationDate` is at or after [since] (the full-enumeration / re-join-seed
     * source, scoped). An implementation MAY return assets before [since] — the caller filters — but MUST
     * NOT omit any at or after it.
     */
    suspend fun walkSince(since: String): List<RawAsset>

    /**
     * Walk exactly the given raw `localIdentifier`s (the incremental changed set), **bounded by [since]**
     * the same way [walkSince] is.
     *
     * The bound is not redundant with the change feed. A change feed reports *what changed*, not *what is
     * in scope*: an iCloud sync or an import can hand back thousands of decades-old assets, and paying one
     * synchronous resource round-trip each — to then drop them all on capture date — cost 166 s for ~1500
     * assets on an iPhone SE2, inside a process with a ~3-minute hard cap. `creationDate` is a plain
     * `PHAsset` property, so out-of-scope assets are rejected before their resources are ever fetched.
     */
    suspend fun walk(localIdentifiers: List<String>, since: String): List<RawAsset>
}
