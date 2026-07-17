package app.snapsync.gallery

/**
 * A settable in-memory [RawAssetSource] for the JVM harness and tests: holds a fixed [RawAsset] list,
 * re-settable via [set]. [walk] filters by **raw** `localIdentifier` membership (the mapping normalizes
 * afterwards) and by the capture-date bound, mirroring how the PhotoKit walk fetches only the requested
 * assets and skips out-of-scope ones; [walkSince] filters by `creationDate`, mirroring the fetch predicate.
 */
class InMemoryRawAssetSource(initial: List<RawAsset> = emptyList()) : RawAssetSource {

    private var all: List<RawAsset> = initial

    /**
     * The current contents, unscoped — a **fake-only** read so the harness can add/remove assets
     * (`current() + newAsset`). Deliberately not on [RawAssetSource]: production has no unbounded walk.
     */
    fun current(): List<RawAsset> = all

    fun set(rawAssets: List<RawAsset>) {
        all = rawAssets
    }

    // Mirrors the real walk's bound only. It deliberately does NOT mirror the PhotoKit fetch predicate's
    // subtype narrowing: that predicate is an optimization the authoritative `commonMain` filter must be
    // proven to work without, so the fake returns the wider set — a screenshot crosses this seam and is
    // dropped downstream, which is exactly the behaviour a real (widened) fetch produces.
    override suspend fun walkSince(since: String): List<RawAsset> = all.filter { it.creationDate >= since }

    override suspend fun walk(localIdentifiers: List<String>, since: String): List<RawAsset> {
        val wanted = localIdentifiers.toSet()
        return all.filter { it.assetId in wanted && it.creationDate >= since }
    }
}
