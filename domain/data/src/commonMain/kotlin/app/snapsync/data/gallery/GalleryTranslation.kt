package app.snapsync.data.gallery

import app.snapsync.domain.asset.AssetFacts
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.MediaKind
import app.snapsync.domain.asset.PixelArea
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.format.AssetIdText
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAssetId

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

/** The grant as the selection reads it: refused and undecided both leave nothing to read. */
fun LibraryGrant.photoAccess(): PhotoAccess = when (this) {
    LibraryGrant.GRANTED -> PhotoAccess.FULL
    LibraryGrant.LIMITED -> PhotoAccess.LIMITED
    LibraryGrant.DENIED, LibraryGrant.NOT_DETERMINED -> PhotoAccess.NONE
}
