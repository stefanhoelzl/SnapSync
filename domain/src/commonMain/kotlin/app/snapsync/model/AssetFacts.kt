package app.snapsync.model

/**
 * One asset as **neutral facts** — the only thing the selection policy decides on (capability
 * `photo-selection-policy`, and `gallery-status`'s *The domain reads neutral asset facts* requirement).
 *
 * Every field is platform-independent. The interpretation of raw PhotoKit values — the `mediaSubtypes`
 * bitmask, the `mediaType` integer — happens in the iOS adapter (`iosMain`), where those constants are
 * pinned against the SDK and can be verified on-platform. `model/` never sees a bitmask, so a second
 * platform produces the same facts from its own media model and the rules are unchanged.
 *
 * **Per asset, never per resource.** An asset's resources stand or fall together: dropping a Live Photo's
 * primary while keeping its paired video leaves an orphan whose bytes nothing uploads. Facts that live on
 * a *resource* (the GIF MIME) are therefore folded up across all of the asset's resources
 * ([factsFromResources]).
 *
 * **The defaults describe an ordinary camera photo, deliberately** — the same reasoning `RawAsset`
 * carried. Every default lands on the *admitted* side of every rule, so a facts value assembled from
 * partial information admits on doubt rather than silently deleting a real photo. In particular
 * [pixelArea] is `null` (unknown), never `0`: a zero area is *below* every floor, so defaulting to it
 * would exclude every asset whose dimensions were not carried.
 */
class AssetFacts(
    val assetId: String,
    val creationDate: CaptureDate,
    val isScreenshot: Boolean = false,
    val isScreenRecording: Boolean = false,
    val isVideo: Boolean = false,
    val isGif: Boolean = false,
    /**
     * The asset has been edited (`hasAdjustments`). A photo cropped in Photos renders at its *cropped*
     * size, so the resolution floors are **skipped** for it — otherwise a genuine capture is dropped for
     * being small, which is exactly the false drop the policy exists to avoid.
     */
    val isEdited: Boolean = false,
    /**
     * `pixelWidth * pixelHeight`, or `null` when the dimensions are unknown — **admit on doubt**. A
     * non-positive area is treated the same way (it means absent, not tiny).
     *
     * One area rather than a separate image/video area: an asset is one or the other, so two fields would
     * make an invalid state representable. Which **floor** applies is decided from [isVideo] by the rules
     * ([SelectionRule.MinImageArea] / [SelectionRule.MinVideoArea]).
     */
    val pixelArea: Long? = null,
)

/**
 * Fold a flat resource list into per-asset [AssetFacts], reading the origin facts the enumerator carried
 * across in each resource's metadata. The GIF signal is checked across **all** of an asset's resources —
 * whichever one is the GIF, the asset is.
 *
 * Absent/unparseable metadata resolves to the admit-on-doubt default, never to an exclusion.
 */
fun factsFromResources(resources: List<Resource>): List<AssetFacts> =
    resources.groupBy { it.assetId }.map { (assetId, group) ->
        val meta = group.first().metadata
        val width = meta[RESOURCE_META_PIXEL_WIDTH]?.toLongOrNull()
        val height = meta[RESOURCE_META_PIXEL_HEIGHT]?.toLongOrNull()
        val subtypes = meta[RESOURCE_META_MEDIA_SUBTYPES]?.toLongOrNull() ?: SUBTYPE_NONE
        AssetFacts(
            assetId = assetId,
            creationDate = CaptureDate(meta[RESOURCE_META_CREATION_DATE] ?: ""),
            isScreenshot = subtypes and SUBTYPE_SCREENSHOT != 0L,
            isScreenRecording = subtypes and SUBTYPE_SCREEN_RECORDING != 0L,
            isVideo = (meta[RESOURCE_META_MEDIA_TYPE]?.toLongOrNull() ?: MEDIA_TYPE_IMAGE) == MEDIA_TYPE_VIDEO,
            isGif = group.any { it.metadata[RESOURCE_META_MIME] == MIME_GIF },
            isEdited = meta[RESOURCE_META_HAS_ADJUSTMENTS]?.toBooleanStrictOrNull() == true,
            pixelArea = if (width != null && height != null) width * height else null,
        )
    }
