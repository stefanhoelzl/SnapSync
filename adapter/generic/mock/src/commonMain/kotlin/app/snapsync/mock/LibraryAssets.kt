package app.snapsync.mock

import app.snapsync.model.AssetFacts
import app.snapsync.model.AssetId
import app.snapsync.model.CaptureDate
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.ResourceRole

/**
 * The photos an operator puts in a [PhotoLibraryMock] — each forged as the platform would have interpreted it (NEUTRAL
 * facts, never a PhotoKit bitmask). The defaults are an ordinary 12 MP camera photo, which the selection policy
 * **admits**; the named kinds are the ones it excludes, and the one it must not.
 */
object LibraryAssets {
    /** The capture date a photo carries unless told otherwise. */
    const val DEFAULT_DATE: String = "2026-06-01T10:00:00Z"

    fun photo(
        assetId: String,
        creationDate: String = DEFAULT_DATE,
        resources: List<RawResource> = listOf(primaryResource()),
        isScreenshot: Boolean = false,
        isScreenRecording: Boolean = false,
        isVideo: Boolean = false,
        pixelWidth: Long = 4032,
        pixelHeight: Long = 3024,
        isEdited: Boolean = false,
    ): RawAsset = RawAsset(
        assetId = AssetId(assetId),
        creationDate = creationDate,
        rawResources = resources,
        facts = AssetFacts(
            assetId = AssetId(assetId),
            creationDate = CaptureDate(creationDate),
            isScreenshot = isScreenshot,
            isScreenRecording = isScreenRecording,
            isVideo = isVideo,
            isEdited = isEdited,
            pixelArea = pixelWidth * pixelHeight,
        ),
    )

    /** A screenshot — excluded by media subtype. */
    fun screenshot(assetId: String, creationDate: String = DEFAULT_DATE): RawAsset =
        photo(assetId, creationDate, isScreenshot = true, pixelWidth = 750, pixelHeight = 1334)

    /** A screen recording — excluded by media subtype. */
    fun screenRecording(assetId: String, creationDate: String = DEFAULT_DATE): RawAsset =
        photo(assetId, creationDate, isScreenRecording = true, isVideo = true, pixelWidth = 886, pixelHeight = 1920)

    /** A messenger-compressed image (1600×1200 ≈ 1.9 MP) — below the image floor. */
    fun lowResPhoto(assetId: String, creationDate: String = DEFAULT_DATE): RawAsset =
        photo(assetId, creationDate, pixelWidth = 1600, pixelHeight = 1200)

    /** A 1080p recording — below the IMAGE floor but above the VIDEO floor, so it must be **admitted**. */
    fun hdVideo(assetId: String, creationDate: String = DEFAULT_DATE): RawAsset =
        photo(assetId, creationDate, isVideo = true, pixelWidth = 1920, pixelHeight = 1080)

    /** A GIF as a messenger saves one (480×270) — excluded by the resolution floor, not by its type. */
    fun gif(assetId: String, creationDate: String = DEFAULT_DATE): RawAsset = photo(
        assetId,
        creationDate,
        resources = listOf(primaryResource(filename = "giphy.gif", contentType = "image/gif")),
        pixelWidth = 480,
        pixelHeight = 270,
    )

    /** A Live Photo: a primary still and its paired motion, two resources that upload separately. */
    fun livePhoto(assetId: String, creationDate: String = DEFAULT_DATE): RawAsset = photo(
        assetId,
        creationDate,
        resources = listOf(
            primaryResource(),
            RawResource(
                role = ResourceRole.LIVE,
                mimeContentType = "video/quicktime",
                originalFilename = "IMG.MOV",
                handle = Unit,
            ),
        ),
    )

    /** A single primary resource, carrying this platform's handle (`Unit`). */
    fun primaryResource(filename: String = "IMG.JPG", contentType: String = "image/jpeg"): RawResource =
        RawResource(
            role = ResourceRole.PRIMARY,
            mimeContentType = contentType,
            originalFilename = filename,
            handle = Unit,
        )
}
