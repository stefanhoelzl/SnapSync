package app.snapsync.mock

import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryResource
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import kotlin.time.Instant

/**
 * The photos an operator puts in a [PhotoLibraryMock] — each described as the platform would have described it
 * (neutral facts, never a PhotoKit bitmask). The defaults are an ordinary 12 MP camera photo, which the selection
 * policy **admits**; the named kinds are the ones it excludes, and the one it must not.
 */
object LibraryAssets {
    /** The capture date a photo carries unless told otherwise. */
    const val DEFAULT_DATE: String = "2026-06-01T10:00:00Z"

    /**
     * A photo whose id is [assetId], captured at [creationDate] — ISO-8601, and a text that does not parse is a photo
     * the platform holds no capture date for.
     */
    fun photo(
        assetId: String,
        creationDate: String = DEFAULT_DATE,
        resources: List<Resource> = listOf(primaryResource()),
        isScreenshot: Boolean = false,
        isScreenRecording: Boolean = false,
        isVideo: Boolean = false,
        pixelWidth: Long = 4032,
        pixelHeight: Long = 3024,
        isEdited: Boolean = false,
    ): GalleryAssetResources {
        val id = PlatformAssetId(assetId)
        return GalleryAssetResources(
            GalleryAsset(
                id = id,
                created = Instant.parseOrNull(creationDate),
                isScreenshot = isScreenshot,
                isScreenRecording = isScreenRecording,
                isVideo = isVideo,
                isEdited = isEdited,
                pixelArea = pixelWidth * pixelHeight,
            ),
            resources.map { GalleryResource(it.kind, it.contentType, it.originalFilename, InMemoryResource(id)) },
        )
    }

    /** A screenshot — excluded by media subtype. */
    fun screenshot(assetId: String, creationDate: String = DEFAULT_DATE): GalleryAssetResources =
        photo(assetId, creationDate, isScreenshot = true, pixelWidth = 750, pixelHeight = 1334)

    /** A screen recording — excluded by media subtype. */
    fun screenRecording(assetId: String, creationDate: String = DEFAULT_DATE): GalleryAssetResources =
        photo(assetId, creationDate, isScreenRecording = true, isVideo = true, pixelWidth = 886, pixelHeight = 1920)

    /** A messenger-compressed image (1600×1200 ≈ 1.9 MP) — below the image floor. */
    fun lowResPhoto(assetId: String, creationDate: String = DEFAULT_DATE): GalleryAssetResources =
        photo(assetId, creationDate, pixelWidth = 1600, pixelHeight = 1200)

    /** A 1080p recording — below the IMAGE floor but above the VIDEO floor, so it must be **admitted**. */
    fun hdVideo(assetId: String, creationDate: String = DEFAULT_DATE): GalleryAssetResources =
        photo(assetId, creationDate, isVideo = true, pixelWidth = 1920, pixelHeight = 1080)

    /** A GIF as a messenger saves one (480×270) — excluded by the resolution floor, not by its type. */
    fun gif(assetId: String, creationDate: String = DEFAULT_DATE): GalleryAssetResources = photo(
        assetId,
        creationDate,
        resources = listOf(primaryResource(filename = "giphy.gif", contentType = "image/gif")),
        pixelWidth = 480,
        pixelHeight = 270,
    )

    /** A Live Photo: a still and its paired motion, two resources that upload separately. */
    fun livePhoto(assetId: String, creationDate: String = DEFAULT_DATE): GalleryAssetResources = photo(
        assetId,
        creationDate,
        resources = listOf(primaryResource(), Resource(ResourceKind.PAIRED_VIDEO, "video/quicktime", "IMG.MOV")),
    )

    /** A photo's still. */
    fun primaryResource(filename: String = "IMG.JPG", contentType: String = "image/jpeg"): Resource =
        Resource(ResourceKind.PHOTO, contentType, filename)

    /** One resource of a photo the operator describes; the library hands it out under the photo's own handle. */
    class Resource(val kind: ResourceKind, val contentType: String, val originalFilename: String)
}

/** The in-memory library's handle of a resource: the asset it belongs to, which holds no bytes. */
data class InMemoryResource(val asset: PlatformAssetId) : GalleryResourceHandle

/** This asset's platform id. */
val GalleryAssetResources.id: PlatformAssetId get() = asset.id
