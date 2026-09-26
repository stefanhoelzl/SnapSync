package app.snapsync.gallery

import app.snapsync.model.AssetId
import app.snapsync.model.isCanonicalAssetId
import co.touchlab.kermit.Logger
import platform.Photos.PHAsset

/**
 * The one mapping between PhotoKit's `localIdentifier` and the canonical [AssetId] — every iOS adapter that
 * hands an asset id out or takes one in goes through it, so the core, the stores and the backend only ever
 * see the canonical form.
 *
 * A `localIdentifier` is `{UUID}/L0/NNN`: the `/` is the only character the canonical rule refuses, and it
 * maps to `_`, which a `localIdentifier` never carries — so the mapping is exact in both directions. The
 * guard makes that an enforced fact rather than an observation: an identifier that already contains `_`, or
 * that maps to something non-canonical, has no id at all ([assetIdOf] answers `null`) instead of one that
 * would fetch a different asset back.
 */
object PhotoKitAssetIds {
    /** The canonical id of [localIdentifier], or `null` when it has no exact one (see the class doc). */
    fun assetIdOf(localIdentifier: String): AssetId? {
        if ('_' in localIdentifier) return null
        return localIdentifier.replace('/', '_').takeIf(::isCanonicalAssetId)?.let(::AssetId)
    }

    /** The `localIdentifier` [id] was minted from — the exact inverse of [assetIdOf]. */
    fun localIdentifierOf(id: AssetId): String = id.value.replace('_', '/')
}

/**
 * [this] asset's canonical id, or `null` — after an `Error` line (so it reaches crash reporting) — when its
 * `localIdentifier` has no exact one. Skipping the asset is the only honest answer: an id that fetched a
 * different asset back would mis-upload or mis-suppress it, and throwing would take every other photo down
 * with it.
 */
internal fun PHAsset.canonicalIdOrReport(): AssetId? =
    PhotoKitAssetIds.assetIdOf(localIdentifier).also {
        if (it == null) Logger.withTag("gallery").e { "asset '$localIdentifier' has no canonical id — skipped" }
    }
