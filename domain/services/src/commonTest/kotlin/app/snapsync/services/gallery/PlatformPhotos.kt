package app.snapsync.services.gallery

import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryResource
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import kotlin.time.Instant

/**
 * A photo as the platform describes it: an ordinary 12 MP camera photo, captured at [created] (`null`: no date), its
 * [resources] each under [TestHandle].
 */
internal fun platformPhoto(
    id: String,
    created: String? = "2026-06-01T10:00:00Z",
    resources: List<Pair<ResourceKind, String>> = listOf(ResourceKind.PHOTO to "IMG.JPG"),
): GalleryAssetResources = GalleryAssetResources(
    GalleryAsset(
        id = PlatformAssetId(id),
        created = created?.let(Instant::parse),
        isScreenshot = false,
        isScreenRecording = false,
        isVideo = false,
        isEdited = false,
        pixelArea = 4032L * 3024L,
    ),
    resources.map { (kind, filename) -> GalleryResource(kind, "image/jpeg", filename, TestHandle) },
)

/** The one resource handle a test library hands out. */
internal object TestHandle : GalleryResourceHandle
