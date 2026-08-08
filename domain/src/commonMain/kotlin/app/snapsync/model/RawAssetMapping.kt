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
            val role = raw.role ?: return@mapNotNull null
            Resource(
                filename = uploadKey(assetId, role, raw.originalFilename),
                assetId = assetId,
                // The resolved MIME, not the platform's own type identifier: this is what the upload
                // provider sends as `Content-Type`, and what the ledger row has always preferred
                // (spec `gallery-status`). The two used to disagree, with the UTI on the wire.
                contentType = raw.mimeContentType,
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
 * The neutral [AssetFacts] of one raw asset, with the id normalized.
 *
 * Every fact reads a plain in-memory platform property, so this is complete whether or not the asset's
 * resources have been fetched — a facts-only walk and a resource-carrying one produce the identical value,
 * which is what lets every consumer resolve the same admitted set at different costs.
 */
fun RawAsset.toFacts(): AssetFacts = AssetFacts(
    assetId = normalizeAssetId(assetId),
    creationDate = CaptureDate(creationDate),
    isScreenshot = facts.isScreenshot,
    isScreenRecording = facts.isScreenRecording,
    isVideo = facts.isVideo,
    isEdited = facts.isEdited,
    pixelArea = facts.pixelArea,
)
