package app.snapsync.gallery

import app.snapsync.model.EXCLUDED_SUBTYPE_MASK
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.ports.PhotoLibrary
import app.snapsync.ports.RawAssetSource

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatWithFractionalSeconds
import platform.Foundation.NSISO8601DateFormatWithInternetDateTime
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSPredicate
import platform.Foundation.dateByAddingTimeInterval
import platform.Photos.PHAsset
import platform.Photos.PHAssetResource
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult
import platform.UniformTypeIdentifiers.UTType

/**
 * The PhotoKit-backed [RawAssetSource]: the **decision-free** library walk (capability `gallery-status`,
 * Move A). Fetches `PHAsset`s, reads each asset's **raw** `localIdentifier` + capture date, walks its
 * `PHAssetResource`s, and emits [RawResource]s carrying only raw facts — the raw `PHAssetResourceType`
 * value, the UTI, the iOS-resolved MIME, the original filename, and the opaque `PHAssetResource` handle.
 * It applies **no** role filter, key derivation, or `assetId` normalization — the shared [resourcesFrom]
 * mapping owns all of that, so the fan-out orchestration is unit-tested off-device.
 *
 * `UTType.preferredMIMEType` (UTI→MIME) stays **iOS-only** — Apple's UTI table must not be reimplemented
 * in `commonMain` — so the MIME is resolved here and carried out as a raw fact.
 *
 * **The walk is bounded and runs off the main thread.** `PHAssetResource.assetResourcesForAsset` is a
 * *synchronous XPC* round-trip into `photolibraryd`'s `Photos.sqlite`, issued once per asset, so the cost
 * is linear in the number of assets fetched. Two defences:
 *
 * 1. The capture-date bound is pushed into the `PHFetchOptions` predicate, so only assets at or after the
 *    membership's cutoff are fetched at all (capability `photo-selection-policy`). Previously the whole library
 *    was walked and the cutoff applied afterwards — thousands of round-trips to keep an evening's photos.
 * 2. Every walk hops to [Dispatchers.Default] — Kotlin/Native has no `Dispatchers.IO` — exactly as
 *    `clearRequestedOffMain` does for the ledger's synchronous DELETE. Both app-process callers reach this
 *    from `SnapSyncRoot`'s `Dispatchers.Main` scope (the status total's `refresh`, and — on the app-driven
 *    18–26.0 tier — the upload pump's discovery), where blocking trips the 10 s scene-update watchdog and
 *    the OS kills the app (`0x8BADF00D`). The extension process calls this too, where the hop is harmless
 *    (`process()` is already off-main).
 *
 * Wiring-only and untestable (PhotoKit, device/simulator only); [PhotoKitSmokeTest] confirms this walk
 * glue runs on the simulator, and the pure mapping it feeds is unit-tested in `commonTest`.
 */
@OptIn(ExperimentalForeignApi::class)
class PhotoLibraryRawAssetSource : RawAssetSource {

    override suspend fun walkSince(since: String): List<RawAsset> = withContext(Dispatchers.Default) {
        // The fetch predicate already bounds this, so the post-check is a cheap no-op here.
        rawAssetsFrom(PHAsset.fetchAssetsWithOptions(fetchOptionsSince(since)), since)
    }

    override suspend fun walk(localIdentifiers: List<String>, since: String): List<RawAsset> =
        withContext(Dispatchers.Default) {
            if (localIdentifiers.isEmpty()) return@withContext emptyList()
            // `fetchAssetsWithLocalIdentifiers` takes no predicate, so the bound is applied per asset in
            // [rawAssetsFrom] — before its resources are touched, which is where the cost lives.
            rawAssetsFrom(PHAsset.fetchAssetsWithLocalIdentifiers(localIdentifiers, null), since)
        }

    /**
     * Push the capture-date bound **and the cheap origin exclusions** into the fetch, so
     * `assetResourcesForAsset` is issued only for assets that could plausibly be admitted — the difference
     * between one round-trip per library asset and one per event photo.
     *
     * **Deliberately widened by [PREDICATE_WIDEN_SECONDS].** The authoritative bound is a *lexicographic*
     * `creationDate >= since` compare on ISO-8601 strings, applied downstream in `commonMain`; this
     * predicate is an `NSDate` comparison. Where the two could disagree at the boundary (fractional
     * seconds, formatter rounding), the asymmetry matters: over-returning costs a few extra round-trips
     * and the downstream filter drops them, while under-returning silently loses a photo that nothing can
     * add back. So the predicate errs wide, and an unparseable bound drops it entirely rather than
     * fetching nothing.
     *
     * **The whole predicate is an optimization, never a decision** (capability `photo-selection-policy`): the
     * `commonMain` filter is authoritative and runs over whatever comes back, so this MAY return a superset
     * of the admitted set but MUST NOT return a subset. That asymmetry is why only the *subtype* exclusion
     * rides along here and the resolution floors do not — see below.
     *
     * **Three device-verified constraints on PhotoKit's predicate parser** (SE2, iOS 26.5.2; these are
     * measured facts, not preferences — re-verify on a device before adding any key):
     *
     * 1. A subtype exclusion MUST be written `NOT ((mediaSubtypes & N) != 0)`. The natural
     *    `(mediaSubtypes & N) == 0` form returns **zero rows** — silently, without raising — even with the
     *    documented plural `mediaSubtypes` key. Shipping it would starve the walk of every asset. (The
     *    *singular* `mediaSubtype` key likewise returns zero rows without raising, so a one-character typo
     *    here empties the library. This is almost certainly the origin of the decade of bug reports.)
     * 2. Predicate **arithmetic** raises an uncatchable `NSException` and aborts the process, so
     *    `pixelWidth * pixelHeight` is impossible. The megapixel floors therefore live **only** in
     *    `commonMain`; a bounding-box approximation could ride along here, but it is deliberately omitted —
     *    it could only ever narrow, and the floors' whole point is to be conservative.
     * 3. `hasAdjustments` is not a supported key and likewise aborts the process. The adjustments guard is
     *    `commonMain`-only.
     */
    private fun fetchOptionsSince(since: String): PHFetchOptions? {
        val bound = parseBound(since) ?: return null
        return PHFetchOptions().apply {
            predicate = NSPredicate.predicateWithFormat(
                // Bit values inlined: they are stable ABI constants, which sidesteps NSNumber boxing.
                // NB the NOT-form — see constraint (1) above. Do not "simplify" this to `== 0`.
                predicateFormat = "creationDate >= %@ AND NOT ((mediaSubtypes & $EXCLUDED_SUBTYPE_MASK) != 0)",
                argumentArray = listOf(bound.dateByAddingTimeInterval(-PREDICATE_WIDEN_SECONDS)),
            )
        }
    }

    /**
     * Parse a cutoff into an `NSDate`, tolerating **fractional seconds**.
     *
     * A bare `NSISO8601DateFormatter` uses `.withInternetDateTime`, which does not accept a `.sss`
     * fraction and returns `nil` for `2026-07-09T19:24:17.182Z`. Cutoffs are supposed to be second
     * precision (capability `photo-selection-policy`), and the join gate now normalizes them — but a cutoff
     * persisted by an older build carries the backend's raw `new Date().toISOString()` milliseconds. Losing
     * the predicate there would silently restore the whole-library fetch that trips the watchdog, so parse
     * both shapes rather than trust the invariant.
     */
    private fun parseBound(since: String): NSDate? {
        NSISO8601DateFormatter().dateFromString(since)?.let { return it }
        val withFraction = NSISO8601DateFormatter().apply {
            formatOptions = NSISO8601DateFormatWithInternetDateTime or NSISO8601DateFormatWithFractionalSeconds
        }
        return withFraction.dateFromString(since)
    }

    /**
     * Map a fetch result to [RawAsset]s, skipping any asset captured before [since] **before** reading its
     * resources. `assetResourcesForAsset` is the expensive call — one synchronous XPC round-trip into
     * `photolibraryd` per asset, measured at ~110 ms each on an iPhone SE2 — while `creationDate` is a
     * plain property read. Rejecting on the cheap fact first is what keeps a change feed full of
     * out-of-scope assets (an iCloud sync, a bulk import) from costing minutes.
     */
    private fun rawAssetsFrom(assets: PHFetchResult, since: String): List<RawAsset> {
        val out = mutableListOf<RawAsset>()
        var index = 0uL
        while (index < assets.count) {
            val asset = assets.objectAtIndex(index) as PHAsset
            index++
            // Per-asset capture timestamp (ISO-8601), reused for every resource of the asset.
            val creationDate = asset.creationDate?.let { NSISO8601DateFormatter().stringFromDate(it) } ?: ""
            // The authoritative compare is lexicographic on this exact shape (capability
            // `photo-selection-policy`); an undated asset sorts before every cutoff and is skipped.
            if (creationDate < since) continue
            val rawResources = PHAssetResource.assetResourcesForAsset(asset).map { any ->
                val resource = any as PHAssetResource
                RawResource(
                    type = resource.type, // raw PHAssetResourceType value — un-mapped
                    contentTypeUti = resource.uniformTypeIdentifier,
                    mimeContentType = UTType.typeWithIdentifier(resource.uniformTypeIdentifier)?.preferredMIMEType
                        ?: "application/octet-stream",
                    originalFilename = resource.originalFilename,
                    handle = resource, // opaque PHAssetResource, crosses uninterpreted
                )
            }
            // The RAW localIdentifier (with '/'); resourcesFrom normalizes it. The five origin facts are
            // plain in-memory `PHAsset` properties — no extra XPC round-trip; the expensive call above is
            // the only one per asset (capability `photo-selection-policy`). They cross as FACTS: nothing is
            // dropped here on a subtype, a dimension, or an adjustment — the upload cycle's filter decides.
            out += RawAsset(
                assetId = asset.localIdentifier,
                creationDate = creationDate,
                rawResources = rawResources,
                mediaSubtypes = asset.mediaSubtypes.toLong(),
                mediaType = asset.mediaType.toLong(),
                pixelWidth = asset.pixelWidth.toLong(),
                pixelHeight = asset.pixelHeight.toLong(),
                hasAdjustments = asset.hasAdjustments,
            )
        }
        return out
    }
}

/** One day of slack on the fetch predicate — see [PhotoLibraryRawAssetSource.fetchOptionsSince]. */
private const val PREDICATE_WIDEN_SECONDS = 24.0 * 60.0 * 60.0

/**
 * The iOS [PhotoLibrary]: the PhotoKit [PhotoLibraryRawAssetSource] walk composed with the
 * shared [resourcesFrom] mapping (via [ResourceEnumerator]). No-arg so the app and extension composition
 * roots keep constructing `PhotoLibraryResourceEnumerator()` unchanged. This is the **single** PhotoKit
 * resource-enumeration site — the upload producer and the re-join seed both go through it, so their keys
 * never diverge.
 */
class PhotoLibraryResourceEnumerator :
    PhotoLibrary by ResourceEnumerator(PhotoLibraryRawAssetSource())
