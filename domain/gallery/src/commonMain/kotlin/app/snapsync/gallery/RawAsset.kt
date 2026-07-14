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

/**
 * The **decision-free** library walk seam: exposes PhotoKit as [RawAsset]s carrying only raw facts. The
 * iOS implementation is PhotoKit-backed; [InMemoryRawAssetSource] stands in on the JVM and simulator so
 * the [resourcesFrom] fan-out mapping is exercised off-device. The walk performs no role filter, key
 * derivation, or `assetId` normalization — every original and non-original resource crosses as a fact.
 *
 * There is **no unbounded walk**: [walkSince] takes a capture-date lower bound, because walking the whole
 * library costs one synchronous PhotoKit round-trip per asset and a membership always has a cutoff to
 * scope it (capability `photo-selection-policy`). The bound is a **scope parameter, not a decision** — what the
 * bound *is* remains a `commonMain` choice; the walk merely receives it, and the caller's own filter stays
 * authoritative over whatever comes back.
 */
interface RawAssetSource {
    /**
     * Walk every asset whose `creationDate` is at or after [since] (the full-enumeration / re-join-seed
     * source, scoped). An implementation MAY return assets before [since] — the caller filters — but MUST
     * NOT omit any at or after it.
     */
    suspend fun walkSince(since: String): List<RawAsset>

    /**
     * Walk exactly the given raw `localIdentifier`s (the incremental changed set), **bounded by [since]**
     * the same way [walkSince] is.
     *
     * The bound is not redundant with the change feed. A change feed reports *what changed*, not *what is
     * in scope*: an iCloud sync or an import can hand back thousands of decades-old assets, and paying one
     * synchronous resource round-trip each — to then drop them all on capture date — cost 166 s for ~1500
     * assets on an iPhone SE2, inside a process with a ~3-minute hard cap. `creationDate` is a plain
     * `PHAsset` property, so out-of-scope assets are rejected before their resources are ever fetched.
     */
    suspend fun walk(localIdentifiers: List<String>, since: String): List<RawAsset>
}

/**
 * A settable in-memory [RawAssetSource] for the JVM harness and tests: holds a fixed [RawAsset] list,
 * re-settable via [set]. [walk] filters by **raw** `localIdentifier` membership (the mapping normalizes
 * afterwards) and by the capture-date bound, mirroring how the PhotoKit walk fetches only the requested
 * assets and skips out-of-scope ones; [walkSince] filters by `creationDate`, mirroring the fetch predicate.
 */
class InMemoryRawAssetSource(initial: List<RawAsset> = emptyList()) : RawAssetSource {

    private var all: List<RawAsset> = initial

    /**
     * The current contents, unscoped — a **fake-only** read so the harness can add/remove assets
     * (`current() + newAsset`). Deliberately not on [RawAssetSource]: production has no unbounded walk.
     */
    fun current(): List<RawAsset> = all

    fun set(rawAssets: List<RawAsset>) {
        all = rawAssets
    }

    // Mirrors the real walk's bound only. It deliberately does NOT mirror the PhotoKit fetch predicate's
    // subtype narrowing: that predicate is an optimization the authoritative `commonMain` filter must be
    // proven to work without, so the fake returns the wider set — a screenshot crosses this seam and is
    // dropped downstream, which is exactly the behaviour a real (widened) fetch produces.
    override suspend fun walkSince(since: String): List<RawAsset> = all.filter { it.creationDate >= since }

    override suspend fun walk(localIdentifiers: List<String>, since: String): List<RawAsset> {
        val wanted = localIdentifiers.toSet()
        return all.filter { it.assetId in wanted && it.creationDate >= since }
    }
}
