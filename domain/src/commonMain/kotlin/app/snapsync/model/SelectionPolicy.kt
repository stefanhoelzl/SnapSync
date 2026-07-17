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
 * **Lives in `:domain:gallery` because it is the only module both consumers can see.** The upload cycle
 * (`:capability:upload`) and the status total (`:domain:status`) must apply the *identical* policy — if they
 * diverge, the status screen pegs below 100% forever — and neither depends on the other. Platform-free and
 * exercised in `commonTest` on JVM and the simulator.
 *
 * The album denylist is **not** here: album membership is a platform lookup, so it enters the cycle as an
 * injected port (its policy lives in `:capability:album`). These rules need only facts already on the
 * resource.
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

/** Whether every resource of one asset is excluded, decided on the facts the enumerator carried across. */
private fun isOriginExcluded(assetResources: List<Resource>): Boolean {
    val first = assetResources.first()
    val meta = first.metadata

    // 1. Screenshots and screen recordings — exact, perfect recall, and the highest-frequency case.
    val subtypes = meta[RESOURCE_META_MEDIA_SUBTYPES]?.toLongOrNull() ?: SUBTYPE_NONE
    if (subtypes and EXCLUDED_SUBTYPE_MASK != 0L) return true

    // 2. Animated images. Checked across ALL of the asset's resources: whichever one is the GIF, the asset is.
    if (assetResources.any { it.metadata[RESOURCE_META_MIME] == MIME_GIF }) return true

    // 3. Resolution floors — compressed received media. Skipped entirely for an EDITED asset: a photo cropped
    //    in Photos renders at its cropped size and would otherwise be mistaken for a compressed download and
    //    dropped, which is precisely the false drop this policy exists to avoid.
    if (meta[RESOURCE_META_HAS_ADJUSTMENTS]?.toBooleanStrictOrNull() == true) return false

    val width = meta[RESOURCE_META_PIXEL_WIDTH]?.toLongOrNull() ?: return false // unknown → admit on doubt
    val height = meta[RESOURCE_META_PIXEL_HEIGHT]?.toLongOrNull() ?: return false
    val area = width * height
    if (area <= 0L) return false // unknown/absent dimensions → admit on doubt, never drop a real photo

    val mediaType = meta[RESOURCE_META_MEDIA_TYPE]?.toLongOrNull() ?: MEDIA_TYPE_IMAGE
    val floor = if (mediaType == MEDIA_TYPE_VIDEO) MIN_VIDEO_PIXEL_AREA else MIN_IMAGE_PIXEL_AREA
    return area < floor
}
