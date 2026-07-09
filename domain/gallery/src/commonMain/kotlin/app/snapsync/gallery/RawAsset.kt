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
 *
 * There is **no unbounded walk**: [walkSince] takes a capture-date lower bound, because walking the whole
 * library costs one synchronous PhotoKit round-trip per asset and a membership always has a cutoff to
 * scope it (capability `photo-date-cutoff`). The bound is a **scope parameter, not a decision** — what the
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

    override suspend fun walkSince(since: String): List<RawAsset> = all.filter { it.creationDate >= since }

    override suspend fun walk(localIdentifiers: List<String>, since: String): List<RawAsset> {
        val wanted = localIdentifiers.toSet()
        return all.filter { it.assetId in wanted && it.creationDate >= since }
    }
}
