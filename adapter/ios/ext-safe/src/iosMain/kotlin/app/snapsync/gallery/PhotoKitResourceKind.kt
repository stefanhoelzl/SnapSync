package app.snapsync.gallery

import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.ResourceKind
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceType
import platform.Photos.PHAssetResourceTypePairedVideo
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAssetResourceTypeVideo

/**
 * The [ResourceKind] of a `PHAssetResourceType`, or `null` when the resource is not an original the port reports — a
 * non-original edit artifact (full-size renders, adjustment data, adjustment-base media), the RAW `alternatePhoto`, a
 * proxy, or audio. Only originals are kept, so an asset's resource set is fixed at capture and never grows.
 *
 * **Absence:** null means "a PhotoKit resource type the port carries no kind for" — one answer for an unknown type and
 * for one deliberately not carried, because the caller skips the resource either way.
 *
 * **Why it lives here.** This is a table over another system's ABI. It used to sit in `model/` as a `when` over the
 * bare integers `1L, 2L, 3L, 9L`, where it was invisible to every gate — an ABI decoder written in primitives is
 * indistinguishable from arithmetic. Naming the constants here makes the table check itself against the SDK
 * (`docs/architecture.md`).
 */
fun photoKitResourceKind(resourceType: PHAssetResourceType): ResourceKind? = when (resourceType) {
    PHAssetResourceTypePhoto -> ResourceKind.PHOTO
    PHAssetResourceTypeVideo -> ResourceKind.VIDEO
    PHAssetResourceTypePairedVideo -> ResourceKind.PAIRED_VIDEO // the Live Photo's original paired video
    else -> null // audio, alternatePhoto (RAW), fullSize*, adjustmentData, adjustmentBase*, proxies
}

/** A PhotoKit resource as the gallery port hands it out: the `PHAssetResource` itself, which only this adapter reads. */
class PhotoKitResource(val resource: PHAssetResource) : GalleryResourceHandle
