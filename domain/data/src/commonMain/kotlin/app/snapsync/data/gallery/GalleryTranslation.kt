package app.snapsync.data.gallery

import app.snapsync.domain.asset.AssetFacts
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.MediaKind
import app.snapsync.domain.asset.PixelArea
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.format.AssetIdText
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind

// The gallery's vocabulary as the selection speaks it — translations, never decisions.

/** The app's id for the asset the platform calls [this] ([AssetIdText]). */
fun PlatformAssetId.assetId(): AssetId = AssetIdText.fromPlatform(value)

/** The platform's id of the asset the app calls [this]. */
fun AssetId.platformId(): PlatformAssetId = PlatformAssetId(AssetIdText.platform(this))

/** What the platform says the asset is. */
fun GalleryAsset.facts(): AssetFacts = AssetFacts(
    assetId = id.assetId(),
    captureDate = CaptureDate.of(created),
    kind = MediaKind.of(isVideo = isVideo, isScreenshot = isScreenshot, isScreenRecording = isScreenRecording),
    isEdited = isEdited,
    pixelArea = PixelArea.of(pixelArea),
)

/** The place a resource of this kind takes in its asset: a still or a video is its primary, motion is live. */
fun ResourceKind.role(): ResourceRole = when (this) {
    ResourceKind.PHOTO, ResourceKind.VIDEO -> ResourceRole.PRIMARY
    ResourceKind.PAIRED_VIDEO -> ResourceRole.LIVE
}
