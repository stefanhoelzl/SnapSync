## Context

Change 3 of three from `docs/sync-refactor.md` (the "Move A" walk seam). Unlike Move B (a relocation),
this **adds a new seam contract** inside `:domain:gallery`, so it carries a real (if lightweight)
`gallery-status` delta.

Verified current state (2026-07-02):

- `:domain:gallery`'s `PhotoLibraryResourceEnumerator` (iosMain, `resourcesForAssets`) interleaves the
  PhotoKit walk with the fan-out mapping in one loop: it reads `asset.localIdentifier`,
  `asset.creationDate`, walks `PHAssetResource.assetResourcesForAsset`, then inline applies
  `resourceRole(resource.type) ?: continue`, `uploadKey(...)`, `localIdentifier.replace('/', '_')`,
  `UTType.typeWithIdentifier(...).preferredMIMEType`, and the `RESOURCE_META_*` stash — building engine
  `Resource`s directly (`PhotoLibraryResourceEnumerator.kt:33-69`).
- The agnostic primitives already exist and are `commonTest`-covered: `resourceRole`, `uploadKey`,
  `fileExtension`, `RESOURCE_META_*` (`UploadKeys.kt`, `UploadKeysTest.kt`).
- The fake `InMemoryGalleryResourceEnumerator` fakes at the `Resource` level, so **the mapping loop is
  exercised only on device** — the gap Move A closes.
- The change-token discovery `IosUploadJobPlatform.discoverResources` decides *which* local identifiers
  to enumerate (full vs `fetchPersistentChangesSinceToken`), then delegates to the enumerator's
  `enumerate()` / `resources(ids)` (`IosUploadJobPlatform.kt:163-192`). That logic is **out of scope**.
- The enumerator seam lives in `:domain:gallery` so it never reaches `:domain:presentation`
  (gallery-status "Module placement keeps the seam off presentation").

## Goals / Non-Goals

**Goals:**
- Split the enumerator into a **decision-free PhotoKit walk** (`RawAssetSource`) + a **pure `commonMain`
  mapping** (`RawAsset` → `Resource`), so the fan-out orchestration runs on JVM + `iosSimulatorArm64`
  with a fake walk.
- Keep `UTType`→MIME resolution iOS-side (a raw fact carried out), never reimplemented in `commonMain`.
- Behavior-preserving: the produced `Resource`s (filename, assetId, contentType, metadata, data) are
  byte-identical to today's.

**Non-Goals:**
- No change to the change-token/change-feed discovery, the `discoverResources`/`Discovery` contract, or
  the `UploadKeys` primitives.
- No behavior change to `ios-background-upload`'s "Resource identity and fan-out" — only its execution
  site relocates into the tested mapping.
- Not fixing the pre-existing `version` language in the enumeration-seam spec (the engine `Resource`
  carries no `version`); out of scope, flagged as an open question.

## Decisions

### D1 — Raw-fact seam types in `:domain:gallery` `commonMain`
```
class RawResource(
    val type: Long,               // raw PHAssetResourceType value (stable ABI)
    val contentTypeUti: String,   // uniformTypeIdentifier
    val mimeContentType: String,  // resolved iOS-side via UTType.preferredMIMEType — a RAW FACT
    val originalFilename: String,
    val handle: Any,              // opaque PHAssetResource, rides uninterpreted into Resource.data
)
class RawAsset(
    val assetId: String,          // RAW localIdentifier (still carrying '/')
    val creationDate: String,     // ISO-8601, resolved iOS-side
    val rawResources: List<RawResource>,
)
```
The walk emits the **raw** `localIdentifier`; normalization is a mapping decision (D2). `handle: Any`
keeps the opaque `PHAssetResource` crossing `commonMain` uninterpreted — the same pattern as
`Resource.data`/`PlatformUploadJob.handle`, which is what lets a JVM fake stand in.

### D2 — Pure `commonMain` mapping `resourcesFrom(rawAssets): List<Resource>`
The extracted loop, now testable: for each `RawAsset`, normalize `assetId` `'/'→'_'`; for each
`RawResource`, `resourceRole(type) ?: skip` (originals-only), build `Resource(filename = uploadKey(...),
assetId, contentType = contentTypeUti, metadata = {creationDate, originalFilename, mimeContentType},
data = handle)`. This is the single site of the fan-out orchestration and the `'/'→'_'` normalization
(consistent with change 1's normalization-consistency contract). *Alternative rejected:* keep the
mapping in the iOS walk and only add a fake at a higher level — that leaves the orchestration untested,
which is the whole point of Move A.

### D3 — `RawAssetSource` is the new fakeable boundary
```
interface RawAssetSource {
    suspend fun walkAll(): List<RawAsset>
    suspend fun walk(localIdentifiers: List<String>): List<RawAsset>
}
```
`GalleryResourceEnumerator` becomes a thin `commonMain` composition
(`enumerate() = resourcesFrom(source.walkAll())`, `resources(ids) = resourcesFrom(source.walk(ids))`).
Add a settable `InMemoryRawAssetSource` fake so tests drive the mapping. The existing
`InMemoryGalleryResourceEnumerator` MAY be retired (its `Resource`-level fakery is superseded) or kept
for callers that want a post-mapping fake — decide at apply time by counting consumers.

### D4 — `UTType`→MIME stays iOS-only
`mimeContentType` is resolved inside the iOS walk (`UTType.preferredMIMEType`, falling back to
`application/octet-stream`) and carried as a raw fact. `commonMain` never sees a `UTType`. Reimplementing
Apple's UTI→MIME table would be a correctness risk (`docs/sync-refactor.md §5`).

### D5 — Change-token discovery unchanged
`IosUploadJobPlatform.discoverResources` still owns the full-vs-incremental decision and the
change-feed read; it just calls the (now walk-composed) enumerator. `Discovery`/`discoverResources`
contract is untouched, so `ios-background-upload` needs no delta.

## Risks / Trade-offs

- **[Silent behavior drift in the extracted mapping]** → The new `commonTest` asserts the produced
  `Resource`s field-by-field against the known-good shape (filename via `uploadKey`, `'/'→'_'`, metadata
  keys/values, originals-only skip, extension), and `PhotoKitSmokeTest` keeps covering the iOS walk glue
  on the simulator. Any drift fails a test.
- **[Opaque `handle` on JVM]** → `handle: Any` holds a `PHAssetResource` on device and any stand-in on
  JVM; the mapping never reads it (passes it to `Resource.data`), so the fake is trivially valid.
- **[Two fakes if `InMemoryGalleryResourceEnumerator` is kept]** → potential confusion over which layer
  to fake. Mitigation: prefer the `RawAssetSource` fake for mapping coverage; retire the old fake unless
  a live consumer needs post-mapping fakery.
- **[Pre-existing `version` spec/code mismatch]** → the enumeration-seam requirement mentions a
  `version`; the engine `Resource` carries none. Not introduced here and not fixed here — flagged below.

## Migration Plan

No runtime/data migration — an internal refactor of `:domain:gallery`. Steps: (1) add `RawAsset`/
`RawResource` + `RawAssetSource` + `resourcesFrom` mapping (commonMain) + `InMemoryRawAssetSource`;
(2) extract the iOS walk from `PhotoLibraryResourceEnumerator` into a `RawAssetSource` impl and make
`GalleryResourceEnumerator` compose walk→map; (3) add the mapping `commonTest`; (4) `./gradlew build` +
`./gradlew compileIosMainKotlinMetadata`; (5) `:domain:gallery` note in the docs. Rollback is a revert.
Sequence: **after** change 1 (shares `:domain:gallery`), independent of change 2, last per §6.

## Open Questions

- **`InMemoryGalleryResourceEnumerator` fate:** retire, or keep alongside `InMemoryRawAssetSource`?
  Decide by consumer count at apply time.
- **`version` in the enumeration-seam spec:** the requirement text names a `version` the engine
  `Resource` does not carry. Reconcile (spec vs code) in a separate docs/spec-accuracy pass — not this
  change.
