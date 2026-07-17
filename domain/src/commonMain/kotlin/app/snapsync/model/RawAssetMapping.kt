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
                    // Origin facts (capability `photo-selection-policy`) — carried, never acted on here.
                    RESOURCE_META_MEDIA_SUBTYPES to asset.mediaSubtypes.toString(),
                    RESOURCE_META_MEDIA_TYPE to asset.mediaType.toString(),
                    RESOURCE_META_PIXEL_WIDTH to asset.pixelWidth.toString(),
                    RESOURCE_META_PIXEL_HEIGHT to asset.pixelHeight.toString(),
                    RESOURCE_META_HAS_ADJUSTMENTS to asset.hasAdjustments.toString(),
                ),
                data = raw.handle,
            )
        }
    }
