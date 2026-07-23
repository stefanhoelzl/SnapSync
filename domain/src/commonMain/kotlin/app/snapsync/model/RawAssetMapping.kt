package app.snapsync.model

/**
 * The **pure fan-out mapping** `RawAsset` → engine `Resource`s — the single site of the fan-out
 * orchestration, extracted from the iOS enumerator so it runs on JVM + the simulator (capability
 * `gallery-status`, Move A). For each [RawAsset]: normalize its `assetId` `'/'→'_'` ([normalizeAssetId]);
 * for each [RawResource], drop it when its raw [RawResource.type] maps to no role
 * ([resourceRole] — originals only), else wrap it as a `Resource` whose `filename` is the shared
 * [uploadKey] and whose `metadata` carries the per-asset manifest detail (creation date, original
 * filename, iOS-resolved MIME). The opaque [RawResource.handle] rides into `Resource.data` uninterpreted.
 * Platform-free, so the role-skip / normalization / key-derivation is exercised without PhotoKit.
 */
fun resourcesFrom(rawAssets: List<RawAsset>): List<Resource> =
    rawAssets.flatMap { asset ->
        val assetId = normalizeAssetId(asset.assetId)
        asset.rawResources.mapNotNull { raw ->
            val role = resourceRole(raw.type) ?: return@mapNotNull null
            Resource(
                filename = uploadKey(assetId, role, raw.originalFilename),
                assetId = assetId,
                contentType = raw.contentTypeUti,
                metadata = mapOf(
                    RESOURCE_META_CREATION_DATE to asset.creationDate,
                    RESOURCE_META_ORIGINAL_FILENAME to raw.originalFilename,
                    RESOURCE_META_MIME to raw.mimeContentType,
                    // Neutral origin facts (capability `photo-selection-policy`) — carried, never acted
                    // on here. Already interpreted by the platform; no PhotoKit value crosses.
                    RESOURCE_META_IS_SCREENSHOT to asset.facts.isScreenshot.toString(),
                    RESOURCE_META_IS_SCREEN_RECORDING to asset.facts.isScreenRecording.toString(),
                    RESOURCE_META_IS_VIDEO to asset.facts.isVideo.toString(),
                    RESOURCE_META_IS_EDITED to asset.facts.isEdited.toString(),
                    RESOURCE_META_PIXEL_AREA to (asset.facts.pixelArea?.toString() ?: ""),
                ),
                data = raw.handle,
            )
        }
    }

/**
 * The neutral [AssetFacts] of one raw asset, with the id normalized and the one **resource**-level fact
 * folded in.
 *
 * The GIF signal lives on the per-resource MIME, not on the asset: it is read from [RawAsset.rawResources]
 * when they are present, and is `false` when a facts-only walk left them empty — the cheap count then
 * *admits* a GIF, which is exactly the policy's admit-on-doubt posture (a stray meme is visible and
 * harmless; the count is a preview, and the cycle's authoritative admission still drops it).
 */
fun RawAsset.toFacts(): AssetFacts = AssetFacts(
    assetId = normalizeAssetId(assetId),
    creationDate = CaptureDate(creationDate),
    isScreenshot = facts.isScreenshot,
    isScreenRecording = facts.isScreenRecording,
    isVideo = facts.isVideo,
    isGif = rawResources.any { it.mimeContentType == MIME_GIF },
    isEdited = facts.isEdited,
    pixelArea = facts.pixelArea,
)
