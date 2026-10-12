package app.snapsync.gallery

import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.PlatformAssetId
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeVideo
import kotlin.math.floor
import kotlin.time.Instant

/**
 * The **PhotoKit → port** interpretation of an asset: the one place a `PHAssetMediaSubtype` bitmask or a
 * `PHAssetMediaType` integer is read, so the port carries booleans and an area and never a PhotoKit value.
 *
 * It lives in `iosMain` because that is where these constants can be **verified against the SDK**. The
 * `:test:architecture` PhotoKit-ABI guard keeps every other module from naming one.
 *
 * Every field is a plain in-memory `PHAsset` property, so this costs **no** extra XPC round-trip — the
 * expensive call is `assetResourcesForAsset` (~110 ms/asset on an SE2), and it is untouched. The capture date is
 * truncated to the second, as every capture date the app compares is.
 */
internal fun PHAsset.toGalleryAsset(): GalleryAsset {
    val subtypes = mediaSubtypes.toLong()
    val width = pixelWidth.toLong()
    val height = pixelHeight.toLong()
    return GalleryAsset(
        id = PlatformAssetId(localIdentifier),
        created = creationDate?.let { Instant.fromEpochSeconds(floor(it.timeIntervalSince1970).toLong()) },
        isScreenshot = subtypes and SUBTYPE_SCREENSHOT != 0L,
        isScreenRecording = subtypes and SUBTYPE_SCREEN_RECORDING != 0L,
        isVideo = mediaType == PHAssetMediaTypeVideo,
        isEdited = hasAdjustments,
        // Unknown or non-positive dimensions are unknown, never a zero area below every floor.
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
 * optimization (see `PhotoKitPredicate`, and the hard-won constraint that the
 * predicate form must be `NOT ((mediaSubtypes & N) != 0)`).
 *
 * It can neither widen nor narrow the admitted set: the in-memory admission re-checks the same facts and
 * stays authoritative.
 */
internal const val EXCLUDED_SUBTYPE_MASK: Long = SUBTYPE_SCREENSHOT or SUBTYPE_SCREEN_RECORDING
