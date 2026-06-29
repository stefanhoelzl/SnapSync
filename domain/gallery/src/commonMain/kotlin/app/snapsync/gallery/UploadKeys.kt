package app.snapsync.gallery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The generic, platform-neutral **role** of an asset resource (capability `asset-manifest`). The role
 * carries the resource's place in its asset, never the platform resource-type name or the media kind
 * (that is `contentType`'s job): [PRIMARY] is the single original primary medium (a still image, a
 * video, or an audio track) and [MOTION] the original paired video of a Live Photo. [wire] is the
 * lowercase token used in object keys and serialized in the manifest (`primary`/`motion`).
 */
@Serializable
enum class ResourceRole(val wire: String) {
    @SerialName("primary")
    PRIMARY("primary"),

    @SerialName("motion")
    MOTION("motion"),
}

/**
 * Map a `PHAssetResourceType` raw value (its raw values are a stable ABI) to the generic [ResourceRole]
 * we back up, or `null` when the resource is **dropped** — a non-original edit artifact (full-size
 * renders, adjustment data, adjustment-base media), the RAW `alternatePhoto`, or a proxy. Only the
 * originals are kept, so an asset's resource set is fixed at capture and never grows: `photo`/`video`/
 * `audio` → [PRIMARY], `pairedVideo` → [MOTION].
 */
fun resourceRole(resourceType: Long): ResourceRole? = when (resourceType) {
    1L, 2L, 3L -> ResourceRole.PRIMARY // photo, video, audio (the asset's single original primary)
    9L -> ResourceRole.MOTION // pairedVideo (the Live Photo's original paired video)
    else -> null // 4 alternatePhoto(RAW), 5/6 fullSize*, 7 adjustmentData, 8 adjustmentBase*, 10 …, proxies
}

/**
 * Pure construction of an asset resource's ledger key / object name — the single place the role-based
 * `"<assetId>-<role>.<ext>"` layout lives, where `assetId` is the PHAsset's `localIdentifier` (v1,
 * single-device) with `/`→`_`. Kept platform-free so the layout is unit-tested on the simulator
 * instead of trapped inside the PhotoKit adapter; the adapter (and the re-join seed) only supply the
 * raw fields. Shared by the upload producer (`:app:ios:photokit-extension`) and the manifest synthesis
 * so a manifest's `filename` is byte-identical to what the producer uploads under.
 */
fun uploadKey(assetId: String, role: ResourceRole, originalFilename: String): String =
    "$assetId-${role.wire}.${fileExtension(originalFilename)}"

/** The lowercased filename extension, or `bin` when the original filename carries none. */
fun fileExtension(originalFilename: String): String =
    originalFilename.substringAfterLast('.', "").ifEmpty { "bin" }.lowercase()
