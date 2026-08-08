package app.snapsync.model

/**
 * A single platform resource as **raw facts**, before any sync/fan-out decision (capability
 * `gallery-status`, the Move A walk seam). The decision-free walk emits these; the pure
 * [resourcesFrom] mapping turns them into engine `Resource`s. No key derivation and no normalization
 * is applied here.
 *
 * Every field is a **platform-independent fact**: the adapter resolves the platform's own encodings
 * before reporting, rather than reporting both forms and leaving the core to pick (spec
 * `module-architecture`). It previously carried a raw `PHAssetResourceType` integer *beside* the
 * role, and an Apple UTI *beside* the resolved MIME — and the core reached for the platform one in
 * both cases.
 *
 * - [role] is the resource's neutral place in its asset, resolved platform-side; `null` means the
 *   resource carries no role we upload and [resourcesFrom] drops it.
 * - [mimeContentType] is resolved **iOS-side** (via `UTType.preferredMIMEType`, falling back to
 *   `application/octet-stream`) — `commonMain` must not reimplement Apple's UTI→MIME table (see the
 *   gallery-status spec).
 * - [handle] is the opaque platform resource; it rides through `commonMain` uninterpreted into
 *   `Resource.data` (a JVM stand-in is valid), exactly as `Resource.data`/`PlatformUploadJob.handle` do.
 */
class RawResource(
    val role: ResourceRole?,
    val mimeContentType: String,
    val originalFilename: String,
    val handle: Any,
)

/**
 * One asset as raw facts: the **raw** `localIdentifier` (still carrying `/` — [resourcesFrom] normalizes
 * it), the iOS-resolved capture [creationDate] (ISO-8601), the neutral origin [facts] below, and every
 * platform [rawResources] (including non-originals; the mapping drops those with no role). The single
 * decision-free unit the walk emits.
 *
 * [facts] are the inputs the selection policy's rules decide on (capability `photo-selection-policy`),
 * and they are **neutral**: the platform interprets its own media model — on iOS the `PHAssetMediaSubtype`
 * bitmask and the `PHAssetMediaType` integer — and emits booleans and an area. `model/` never sees a
 * PhotoKit value, so a second platform produces the same facts from its own model and the rules are
 * unchanged (capability `gallery-status`).
 *
 * They cross as **facts, not decisions** — the walk never drops an asset on any of them; the one
 * admission does. All of them derive from plain in-memory `PHAsset` properties, so carrying them costs
 * **no** additional PhotoKit round-trip: the expensive call is `assetResourcesForAsset` (~110 ms/asset on
 * an SE2), and it is untouched.
 *
 * [AssetFacts]'s own defaults describe an ordinary camera photo, deliberately — see its doc. A test or
 * fake asset is therefore admitted unless it opts into an exclusion.
 */
class RawAsset(
    val assetId: String,
    val creationDate: String,
    val rawResources: List<RawResource>,
    val facts: AssetFacts = AssetFacts(assetId = assetId, creationDate = CaptureDate(creationDate)),
)
