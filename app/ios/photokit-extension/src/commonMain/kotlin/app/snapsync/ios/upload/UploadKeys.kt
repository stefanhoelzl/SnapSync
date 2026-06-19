package app.snapsync.ios.upload

/**
 * Pure construction of an asset resource's ledger key / object name — the single place the
 * `"<assetId>-<kind>.<ext>"` layout (design.md §3.1) lives, where `assetId` is the PHAsset's
 * `localIdentifier` (v1, single-device). Kept platform-free so the layout is unit-tested on the
 * simulator instead of trapped inside the PhotoKit adapter; the adapter only supplies the raw fields.
 */
internal fun uploadKey(assetId: String, resourceType: Long, originalFilename: String): String =
    "$assetId-${resourceKind(resourceType)}.${fileExtension(originalFilename)}"

/**
 * The open platform resource-kind segment, mirroring `PHAssetResourceType` (whose raw values are a
 * stable ABI). Unknown types fall back to `ios.type<n>` so a new kind still produces a distinct,
 * deterministic key rather than colliding.
 */
internal fun resourceKind(resourceType: Long): String = when (resourceType) {
    1L -> "ios.photo"
    2L -> "ios.video"
    3L -> "ios.audio"
    4L -> "ios.alternatePhoto"
    5L -> "ios.fullSizePhoto"
    6L -> "ios.fullSizeVideo"
    7L -> "ios.adjustmentData"
    8L -> "ios.adjustmentBasePhoto"
    9L -> "ios.pairedVideo"
    10L -> "ios.fullSizePairedVideo"
    else -> "ios.type$resourceType"
}

/** The lowercased filename extension, or `bin` when the original filename carries none. */
internal fun fileExtension(originalFilename: String): String =
    originalFilename.substringAfterLast('.', "").ifEmpty { "bin" }.lowercase()
