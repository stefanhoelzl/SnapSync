package app.snapsync.ports

import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * An asset's id as the platform itself writes it — PhotoKit's `localIdentifier`, MediaStore's `_ID` — and the one
 * the gallery finds the asset by again. Opaque: no adapter rewrites it, and what the app keys its records on is the
 * gallery services' encoding of it.
 */
@JvmInline
value class PlatformAssetId(val value: String)

/**
 * One asset as the platform describes it, read from its in-memory properties alone — no resource is fetched for any
 * of these, so a facts-only read and a resource-carrying one describe the asset identically.
 */
class GalleryAsset(
    val id: PlatformAssetId,
    /** When it was captured; `null` when the platform holds no capture date for it. */
    val created: Instant?,
    val isScreenshot: Boolean,
    val isScreenRecording: Boolean,
    val isVideo: Boolean,
    /** The platform holds an edit of it (iOS's adjustments): what it renders is not what was captured. */
    val isEdited: Boolean,
    /** Its width times its height in pixels, or `null` when the platform reports no dimensions. */
    val pixelArea: Long?,
)

/** An asset with its original resources, each of a [ResourceKind]: a resource of no kind is never reported. */
class GalleryAssetResources(val asset: GalleryAsset, val resources: List<GalleryResource>)

/** One of an asset's original media files. */
class GalleryResource(
    val kind: ResourceKind,
    /** Its MIME type, resolved by the adapter from the platform's own type identifier. */
    val contentType: String,
    val originalFilename: String,
    val handle: GalleryResourceHandle,
)

/** What an original resource is to its asset — as captured, never an edit, a render, a RAW alternate or a proxy. */
enum class ResourceKind {
    /** The still of a photo. */
    PHOTO,

    /** The video of a video asset. */
    VIDEO,

    /** The motion a photo carries beside its still (a Live Photo's, a Motion Photo's). */
    PAIRED_VIDEO,
}

/** The platform's own resource, opaque above the adapter that handed it out — and handed back only to that adapter. */
interface GalleryResourceHandle

/**
 * The whole current selection under a **partial** grant, every asset with its resources: a snapshot, never a delta —
 * platform change details are unreliable for bulk changes (measured: a batched create reports no itemized inserts).
 * It carries the resources read with it because under a partial grant the app may read only on the cold-launch
 * baseline and on an observer emission, never go back to the gallery on its own.
 */
class SelectionSnapshot(val assets: List<GalleryAssetResources>)

/** What an import did, as the platform reported it. */
sealed interface ImportOutcome {
    /** The asset was created as [created]. */
    data class Imported(val created: PlatformAssetId) : ImportOutcome

    /**
     * The import did not complete — the platform reported the change failed. [consumedResources]: the platform took
     * the files before it failed (it ingests a resource before validating it), so nothing is left to retry from; an
     * adapter that cannot tell answers `false`. [placeholder]: the asset the change block had reported creating, or
     * `null` when it reported none.
     */
    data class Failed(
        val message: String,
        val consumedResources: Boolean = false,
        val placeholder: PlatformAssetId? = null,
    ) : ImportOutcome
}
