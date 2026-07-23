package app.snapsync.gallery

import app.snapsync.model.AssetFacts
import app.snapsync.model.CaptureDate
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeVideo

/**
 * The **PhotoKit → neutral facts** interpretation (capability `gallery-status`, *The domain reads neutral
 * asset facts*): the one place a `PHAssetMediaSubtype` bitmask or a `PHAssetMediaType` integer is read.
 *
 * It lives in `iosMain` because that is where these constants can be **verified against the SDK**. A copy
 * in `commonMain` would be asserted, in a JVM test, against another copy — the exact drift shape
 * `RuntimeIdentityTest` exists to catch. `:domain` therefore decides admission on booleans and an area
 * and never names a PhotoKit value; the `:test:architecture` PhotoKit-ABI guard keeps it that way.
 *
 * Every field is a plain in-memory `PHAsset` property, so this costs **no** extra XPC round-trip — the
 * expensive call is `assetResourcesForAsset` (~110 ms/asset on an SE2), and it is untouched.
 *
 * The GIF fact is deliberately absent here: it lives on a *resource*'s MIME type, not on the asset, and
 * is folded in by `RawAsset.toFacts()` once the resources are known.
 */
internal fun PHAsset.toAssetFacts(creationDate: String): AssetFacts {
    val subtypes = mediaSubtypes.toLong()
    val width = pixelWidth.toLong()
    val height = pixelHeight.toLong()
    return AssetFacts(
        assetId = localIdentifier,
        creationDate = CaptureDate(creationDate),
        isScreenshot = subtypes and SUBTYPE_SCREENSHOT != 0L,
        isScreenRecording = subtypes and SUBTYPE_SCREEN_RECORDING != 0L,
        isVideo = mediaType == PHAssetMediaTypeVideo,
        isEdited = hasAdjustments,
        // Unknown or non-positive dimensions cross as `null` — the policy admits on doubt rather than
        // treating a zero area as "below every floor", which would delete real photos silently.
        pixelArea = (width * height).takeIf { width > 0 && height > 0 },
    )
}

/**
 * `PHAssetMediaSubtype.photoScreenshot` — `1 shl 2`. Pinned here, against the SDK, rather than in
 * `commonMain`: it is a platform ABI value, and this is the only module that may read one.
 */
internal const val SUBTYPE_SCREENSHOT: Long = 1L shl 2

/** `PHAssetMediaSubtype.videoScreenRecording` — `1 shl 19`. Runtime-present since iOS 13. */
internal const val SUBTYPE_SCREEN_RECORDING: Long = 1L shl 19

/**
 * The subtype bits that exclude an asset outright — inlined into the fetch predicate as a **narrowing**
 * optimization (see `PhotoLibraryRawAssetSource.fetchOptionsSince`, and the hard-won constraint that the
 * predicate form must be `NOT ((mediaSubtypes & N) != 0)`).
 *
 * It can neither widen nor narrow the admitted set: the in-memory admission re-checks the same facts and
 * stays authoritative (capability `photo-selection-policy`).
 */
internal const val EXCLUDED_SUBTYPE_MASK: Long = SUBTYPE_SCREENSHOT or SUBTYPE_SCREEN_RECORDING
