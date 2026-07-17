package app.snapsync.gallery

/**
 * A single platform resource as **raw facts**, before any sync/fan-out decision (capability
 * `gallery-status`, the Move A walk seam). The decision-free walk ([RawAssetSource]) emits these; the
 * pure [resourcesFrom] mapping turns them into engine `Resource`s. No role filter, no key derivation,
 * no normalization is applied here.
 *
 * - [type] is the **raw** `PHAssetResourceType` value (a stable ABI integer), un-mapped to any role.
 * - [mimeContentType] is resolved **iOS-side** (via `UTType.preferredMIMEType`) and carried out as a
 *   raw fact — `commonMain` must not reimplement Apple's UTI→MIME table (see the gallery-status spec).
 * - [handle] is the opaque `PHAssetResource`; it rides through `commonMain` uninterpreted into
 *   `Resource.data` (a JVM stand-in is valid), exactly as `Resource.data`/`PlatformUploadJob.handle` do.
 */
class RawResource(
    val type: Long,
    val contentTypeUti: String,
    val mimeContentType: String,
    val originalFilename: String,
    val handle: Any,
)

/**
 * One asset as raw facts: the **raw** `localIdentifier` (still carrying `/` — [resourcesFrom] normalizes
 * it), the iOS-resolved capture [creationDate] (ISO-8601), the **origin facts** below, and every platform
 * [rawResources] (including non-originals; the mapping drops those with no role). The single decision-free
 * unit the walk emits.
 *
 * The origin facts are the inputs the selection policy's exclusion rules decide on (capability
 * `photo-selection-policy`). They cross as **facts, not decisions** — the walk never drops an asset on any of
 * them; the authoritative filter in the upload cycle does. All five are plain in-memory `PHAsset` properties,
 * so carrying them costs **no** additional PhotoKit round-trip: the expensive call is
 * `assetResourcesForAsset` (~110 ms/asset on an SE2), and it is untouched.
 *
 * - [mediaSubtypes] is the **raw** `PHAssetMediaSubtype` bitmask (a stable ABI integer). Screenshot is
 *   `1 shl 2`, screen recording `1 shl 19`.
 * - [mediaType] is the **raw** `PHAssetMediaType` value (`1` image, `2` video).
 * - [pixelWidth]/[pixelHeight] are the asset's dimensions — the resolution floors' input.
 * - [hasAdjustments] means the asset has been edited. A photo cropped in Photos renders at its *cropped*
 *   size, so the floors are skipped for it — otherwise a genuine capture would be dropped for being small.
 *
 * **The defaults describe an ordinary camera photo, deliberately.** The PhotoKit walk always populates all
 * five; the defaults exist for the in-memory fakes and test builders, and they must land on the *admitted*
 * side of every rule. Defaulting [pixelWidth]/[pixelHeight] to `0` instead would make a default fake asset
 * zero-area — i.e. **below the resolution floor, and therefore excluded** — silently deleting every fake
 * asset from the harnesses and the test suite. A fake is an admitted camera photo unless it opts into an
 * exclusion, which is also the admit-on-doubt direction the policy takes everywhere else.
 */
class RawAsset(
    val assetId: String,
    val creationDate: String,
    val rawResources: List<RawResource>,
    val mediaSubtypes: Long = SUBTYPE_NONE,
    val mediaType: Long = MEDIA_TYPE_IMAGE,
    val pixelWidth: Long = DEFAULT_FAKE_PIXEL_WIDTH,
    val pixelHeight: Long = DEFAULT_FAKE_PIXEL_HEIGHT,
    val hasAdjustments: Boolean = false,
)

/** Raw `PHAssetMediaType` values (stable ABI integers), carried out of the walk un-mapped. */
const val MEDIA_TYPE_IMAGE: Long = 1
const val MEDIA_TYPE_VIDEO: Long = 2

/** No subtype bits set — the common case for a camera capture. */
const val SUBTYPE_NONE: Long = 0

/** 4032×3024 — an SE2 back-camera photo (12.2 MP). See the note on [RawAsset] about why this is the default. */
private const val DEFAULT_FAKE_PIXEL_WIDTH: Long = 4032
private const val DEFAULT_FAKE_PIXEL_HEIGHT: Long = 3024
