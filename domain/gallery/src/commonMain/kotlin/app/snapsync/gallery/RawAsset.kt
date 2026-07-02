package app.snapsync.gallery

/**
 * A single platform resource as **raw facts**, before any sync/fan-out decision (capability
 * `gallery-status`, the Move A walk seam). The decision-free walk ([RawAssetSource]) emits these; the
 * pure [resourcesFrom] mapping turns them into engine `Resource`s. No role filter, no key derivation,
 * no normalization is applied here.
 *
 * - [type] is the **raw** `PHAssetResourceType` value (a stable ABI integer), un-mapped to any role.
 * - [mimeContentType] is resolved **iOS-side** (via `UTType.preferredMIMEType`) and carried out as a
 *   raw fact — `commonMain` must not reimplement Apple's UTI→MIME table (see `docs/design.md §…`).
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
 * it), the iOS-resolved capture [creationDate] (ISO-8601), and every platform [rawResources] (including
 * non-originals; the mapping drops those with no role). The single decision-free unit the walk emits.
 */
class RawAsset(
    val assetId: String,
    val creationDate: String,
    val rawResources: List<RawResource>,
)

/**
 * The **decision-free** library walk seam: exposes PhotoKit as [RawAsset]s carrying only raw facts. The
 * iOS implementation is PhotoKit-backed; [InMemoryRawAssetSource] stands in on the JVM and simulator so
 * the [resourcesFrom] fan-out mapping is exercised off-device. The walk performs no role filter, key
 * derivation, or `assetId` normalization — every original and non-original resource crosses as a fact.
 */
interface RawAssetSource {
    /** Walk the whole library (the full-enumeration / re-join-seed source). */
    suspend fun walkAll(): List<RawAsset>

    /** Walk exactly the given raw `localIdentifier`s (the incremental changed set). */
    suspend fun walk(localIdentifiers: List<String>): List<RawAsset>
}

/**
 * A settable in-memory [RawAssetSource] for the JVM harness and tests: holds a fixed [RawAsset] list,
 * re-settable via [set]. [walk] filters by **raw** `localIdentifier` membership (the mapping normalizes
 * afterwards), mirroring how the PhotoKit walk fetches only the requested assets.
 */
class InMemoryRawAssetSource(initial: List<RawAsset> = emptyList()) : RawAssetSource {

    private var all: List<RawAsset> = initial

    fun set(rawAssets: List<RawAsset>) {
        all = rawAssets
    }

    override suspend fun walkAll(): List<RawAsset> = all

    override suspend fun walk(localIdentifiers: List<String>): List<RawAsset> {
        val wanted = localIdentifiers.toSet()
        return all.filter { it.assetId in wanted }
    }
}
