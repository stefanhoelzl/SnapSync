package app.snapsync.gallery

import app.snapsync.model.ResourceRole
import platform.Photos.PHAssetResourceType
import platform.Photos.PHAssetResourceTypeAudio
import platform.Photos.PHAssetResourceTypePairedVideo
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAssetResourceTypeVideo

/**
 * Map a `PHAssetResourceType` to the generic [ResourceRole] we upload, or `null` when the resource is
 * **dropped** — a non-original edit artifact (full-size renders, adjustment data, adjustment-base
 * media), the RAW `alternatePhoto`, or a proxy. Only the originals are kept, so an asset's resource
 * set is fixed at capture and never grows: `photo`/`video`/`audio` → `PRIMARY`, `pairedVideo` →
 * `LIVE`.
 *
 * **Absence:** null means "a PhotoKit resource type this policy carries no role for" — one answer for
 * an unknown type and for one deliberately not carried, because the caller skips the resource either
 * way. Nothing here can fail as opposed to not-match.
 *
 * **Why it lives here.** This is a table over another system's ABI. It used to sit in `model/` as a
 * `when` over the bare integers `1L, 2L, 3L, 9L`, where it was invisible to every gate — an ABI
 * decoder written in primitives is indistinguishable from arithmetic — and where a second platform's
 * resource-type integers would have collided with Apple's rather than failing. Naming the constants
 * here makes the table check itself against the SDK (spec `module-architecture`).
 */
fun photoKitResourceRole(resourceType: PHAssetResourceType): ResourceRole? = when (resourceType) {
    PHAssetResourceTypePhoto,
    PHAssetResourceTypeVideo,
    PHAssetResourceTypeAudio,
    -> ResourceRole.PRIMARY // the asset's single original primary medium

    PHAssetResourceTypePairedVideo -> ResourceRole.LIVE // the Live Photo's original paired video

    else -> null // alternatePhoto (RAW), fullSize*, adjustmentData, adjustmentBase*, proxies
}
