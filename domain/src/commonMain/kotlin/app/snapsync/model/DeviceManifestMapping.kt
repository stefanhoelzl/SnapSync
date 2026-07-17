package app.snapsync.model


/**
 * Build [DeviceManifestAsset]s from the cycle's discovered [resources] — grouping by `assetId`, parsing
 * the [ResourceRole] from each upload key, and reading `creationDate`/original filename/MIME from the
 * [Resource.metadata] the iOS enumerator stashed there. This reuses the upload cycle's **single**
 * enumeration, so the device manifest needs no second PhotoKit pass (the pass that hung the extension).
 * The manifest resource's [ManifestResource.key] is the engine's upload key ([Resource.filename]); its
 * [ManifestResource.filename] is the human capture name from metadata.
 */
fun deviceManifestAssetsFromResources(resources: List<Resource>): List<DeviceManifestAsset> =
    resources.groupBy { it.assetId }.map { (assetId, group) ->
        DeviceManifestAsset(
            assetId = assetId,
            creationDate = group.first().metadata[RESOURCE_META_CREATION_DATE].orEmpty(),
            resources = group.map { r ->
                ManifestResource(
                    role = roleFromUploadKey(r.filename),
                    contentType = r.metadata[RESOURCE_META_MIME] ?: "application/octet-stream",
                    key = r.filename,
                    filename = r.metadata[RESOURCE_META_ORIGINAL_FILENAME].orEmpty(),
                )
            },
        )
    }
