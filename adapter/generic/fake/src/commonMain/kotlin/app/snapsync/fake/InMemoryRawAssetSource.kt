package app.snapsync.fake

import app.snapsync.model.RawAsset
import app.snapsync.ports.RawAssetSource
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An honest in-memory [RawAssetSource]: reads a constructor-injected state cell, so whoever owns the
 * cell (a test, the world's gallery wrapper) adds and removes assets — the fake itself exposes only
 * the port (the honesty gate). [walk] filters by **raw** `localIdentifier` membership (the mapping
 * normalizes afterwards) and by the capture-date bound, mirroring how the PhotoKit walk fetches only
 * the requested assets and skips out-of-scope ones; [walkSince] filters by `creationDate`, mirroring
 * the fetch predicate.
 */
class InMemoryRawAssetSource(private val state: MutableStateFlow<List<RawAsset>>) : RawAssetSource {

    constructor(initial: List<RawAsset> = emptyList()) : this(MutableStateFlow(initial))

    // Mirrors the real walk's bound only. It deliberately does NOT mirror the PhotoKit fetch predicate's
    // subtype narrowing: that predicate is an optimization the authoritative `commonMain` filter must be
    // proven to work without, so the fake returns the wider set — a screenshot crosses this seam and is
    // dropped downstream, which is exactly the behaviour a real (widened) fetch produces.
    override suspend fun walkSince(since: String): List<RawAsset> = state.value.filter { it.creationDate >= since }

    override suspend fun walk(localIdentifiers: List<String>, since: String): List<RawAsset> {
        val wanted = localIdentifiers.toSet()
        return state.value.filter { it.assetId in wanted && it.creationDate >= since }
    }

    // The facts-only walk: same capture-date bound as `walkSince`. The in-memory assets already carry only
    // facts (their `rawResources` are whatever the test set), so there is nothing extra to strip — the real
    // adapter's saving is skipping the per-asset resource round-trip, which this fake never made anyway.
    override suspend fun factsSince(since: String): List<RawAsset> =
        state.value.filter { it.creationDate >= since }
}
