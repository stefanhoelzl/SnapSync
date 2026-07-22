package app.snapsync.model


/**
 * The **origin exclusions** of the selection policy (capability `photo-selection-policy`): which of the
 * device's photos are certainly *not* captures, and therefore never enter an event.
 *
 * The cutoff answers *when was it taken*; this answers *what is it*. Inside the event window a camera roll
 * also accumulates screenshots, memes, and media received over messaging apps — none of which anyone at the
 * event took, and all of which would otherwise upload to the event and land on every other member's phone.
 *
 * **This can only subtract, never infer.** PhotoKit exposes no "this device's camera took this" flag on any
 * iOS through 26 — the entire public surface of `PHAsset`/`PHAssetResource` was enumerated against the 26.5
 * SDK headers and there is nothing. So the rules below each recognize a category that is *certainly* not a
 * capture, and everything else is **admitted**.
 *
 * **Admit on doubt.** Where a rule cannot distinguish received media from a capture, it admits. The asymmetry
 * is deliberate and it is not squeamishness: a stray uploaded meme is visible, harmless, and deletable, while
 * an event photo that silently fails to upload is a failure of the product's core promise on a surface where
 * the user cannot even notice it, let alone correct it. This is why there is a resolution *floor* and not a
 * resolution *allowlist*, and why the floors are skipped for edited assets.
 *
 * **Lives in `model/` because it is the only zone both consumers can see.** The upload cycle
 * (`:domain` feature/upload) and the status total (feature/status) must apply the *identical* policy — if they
 * diverge, the status screen pegs below 100% forever — and neither feature may reference the other.
 * Platform-free and exercised in `commonTest` on JVM and the simulator.
 *
 * The album denylist is **not** here as a rule: album membership is a platform lookup, so it enters the
 * cycle as an injected port ([DENYLISTED_ALBUM_TITLES] below carries the titles; the lookup lives behind
 * the `AlbumManager` port). These rules need only facts already on the resource.
 */

/** `PHAssetMediaSubtype.photoScreenshot` — `1 shl 2`. */
const val SUBTYPE_SCREENSHOT: Long = 1L shl 2

/** `PHAssetMediaSubtype.videoScreenRecording` — `1 shl 19`. Runtime-present since iOS 13. */
const val SUBTYPE_SCREEN_RECORDING: Long = 1L shl 19

/**
 * The subtype bits that exclude an asset outright. Also inlined into the iOS fetch predicate as an
 * optimization — see `PhotoLibraryRawAssetSource.fetchOptionsSince`, and note the hard-won constraint that
 * the predicate form must be `NOT ((mediaSubtypes & N) != 0)`.
 */
const val EXCLUDED_SUBTYPE_MASK: Long = SUBTYPE_SCREENSHOT or SUBTYPE_SCREEN_RECORDING

/** A GIF is never a camera capture — including one exported from a Live Photo, which is a re-encode. */
const val MIME_GIF: String = "image/gif"

/**
 * Images below **3 MP** are excluded. WhatsApp caps received images at a 1600 long edge (~1.9 MP, at worst
 * 2.6 MP square), Telegram ~1.2 MP, an Instagram save ~1.5 MP — while the *weakest* camera on the oldest
 * supported device (the SE2 front camera) is 3088×2320 = 7.2 MP. The floor sits >2× below that.
 *
 * **Fixed, not derived from `AVCaptureDevice` at runtime.** A device-derived floor is *tighter* on a better
 * camera, and tighter means more false drops — the opposite of admit-on-doubt. The device's real camera
 * resolution is information this policy deliberately declines to act on.
 */
const val MIN_IMAGE_PIXEL_AREA: Long = 3_000_000

/**
 * Videos below **1280×720** are excluded — a *separate, lower* floor, and this is load-bearing rather than a
 * refinement. 1080p video is 1920×1080 = **2.07 MP, which is below [MIN_IMAGE_PIXEL_AREA]**, so a single
 * shared floor would silently drop every 1080p recording — and 1080p is the iOS capture default. That is the
 * most expensive mistake available in this policy.
 */
const val MIN_VIDEO_PIXEL_AREA: Long = 1280L * 720L

/**
 * The asset ids among [resources] that the origin rules exclude. Per-**asset**, not per-resource: an asset's
 * resources stand or fall together, or a GIF's primary would be dropped while its paired video survived as an
 * orphan whose bytes nothing uploads.
 *
 * Returns the excluded ids rather than the surviving resources so it composes exactly like the echo-
 * suppression port the cycle already has (`filterNot { it.assetId in excluded }`).
 */
fun excludedAssetIds(resources: List<Resource>): Set<String> =
    resources.groupBy { it.assetId }
        .filterValues { group -> isOriginExcluded(group) }
        .keys

/**
 * The asset ids among [assets] that the origin rules exclude — the **cheap-facts** twin of
 * [excludedAssetIds], for the join-time shareable-count preview (capability `join-share-count`). It reads
 * the origin facts straight off each [RawAsset] (all plain in-memory `PHAsset` properties, no
 * `assetResourcesForAsset` round-trip) and delegates to the **same** [isOriginExcludedFacts] rule as the
 * resource path, so the preview and the upload cycle share one policy rather than two copies.
 *
 * The **GIF** signal is the one fact that lives on the per-resource MIME, not on the asset: it is read from
 * [RawAsset.rawResources] when they are present, and is `false` when a facts-only walk left them empty — the
 * cheap count then *admits* a GIF, which is exactly the policy's admit-on-doubt posture (a stray meme is
 * visible and harmless; the count is a preview, not the authoritative filter — the cycle still drops it).
 */
fun originExcludedAssetIds(assets: List<RawAsset>): Set<String> =
    assets.asSequence()
        .filter { asset ->
            isOriginExcludedFacts(
                subtypes = asset.mediaSubtypes,
                isGif = asset.rawResources.any { it.mimeContentType == MIME_GIF },
                hasAdjustments = asset.hasAdjustments,
                mediaType = asset.mediaType,
                pixelWidth = asset.pixelWidth,
                pixelHeight = asset.pixelHeight,
            )
        }
        .map { it.assetId }
        .toSet()

/** Whether every resource of one asset is excluded, decided on the facts the enumerator carried across. */
private fun isOriginExcluded(assetResources: List<Resource>): Boolean {
    val meta = assetResources.first().metadata
    return isOriginExcludedFacts(
        subtypes = meta[RESOURCE_META_MEDIA_SUBTYPES]?.toLongOrNull() ?: SUBTYPE_NONE,
        // Checked across ALL of the asset's resources: whichever one is the GIF, the asset is.
        isGif = assetResources.any { it.metadata[RESOURCE_META_MIME] == MIME_GIF },
        hasAdjustments = meta[RESOURCE_META_HAS_ADJUSTMENTS]?.toBooleanStrictOrNull() == true,
        mediaType = meta[RESOURCE_META_MEDIA_TYPE]?.toLongOrNull() ?: MEDIA_TYPE_IMAGE,
        // Absent dimensions parse to null → admit on doubt (never drop a real photo).
        pixelWidth = meta[RESOURCE_META_PIXEL_WIDTH]?.toLongOrNull(),
        pixelHeight = meta[RESOURCE_META_PIXEL_HEIGHT]?.toLongOrNull(),
    )
}

/**
 * The one origin-exclusion rule, decided on primitive facts, shared by the resource path
 * ([excludedAssetIds]) and the cheap-facts path ([originExcludedAssetIds]) so the policy is never forked.
 *
 * Null [pixelWidth]/[pixelHeight] means the dimensions are unknown → **admit on doubt**.
 */
private fun isOriginExcludedFacts(
    subtypes: Long,
    isGif: Boolean,
    hasAdjustments: Boolean,
    mediaType: Long,
    pixelWidth: Long?,
    pixelHeight: Long?,
): Boolean {
    // 1. Screenshots and screen recordings — exact, perfect recall, and the highest-frequency case.
    if (subtypes and EXCLUDED_SUBTYPE_MASK != 0L) return true

    // 2. Animated images. A GIF is never a camera capture.
    if (isGif) return true

    // 3. Resolution floors — compressed received media. Skipped entirely for an EDITED asset: a photo cropped
    //    in Photos renders at its cropped size and would otherwise be mistaken for a compressed download and
    //    dropped, which is precisely the false drop this policy exists to avoid.
    if (hasAdjustments) return false

    val width = pixelWidth ?: return false // unknown → admit on doubt
    val height = pixelHeight ?: return false
    val area = width * height
    if (area <= 0L) return false // unknown/absent dimensions → admit on doubt, never drop a real photo

    val floor = if (mediaType == MEDIA_TYPE_VIDEO) MIN_VIDEO_PIXEL_AREA else MIN_IMAGE_PIXEL_AREA
    return area < floor
}
