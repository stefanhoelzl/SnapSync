## Why

The iOS PhotoKit enumerator (`:domain:gallery`'s `PhotoLibraryResourceEnumerator`) **interleaves** two
things in one iosMain, untested loop: the **decision-free PhotoKit walk** (fetch `PHAsset`s, read
`localIdentifier`/`creationDate`, walk `PHAssetResource`, read raw type / UTI / original filename /
`UTType.preferredMIMEType`) and the **agnostic fan-out mapping** (originals filter via `resourceRole`,
`uploadKey` derivation, `assetId` `'/'→'_'` normalization, manifest-metadata stashing). The fan-out
*policy* primitives (`resourceRole`, `uploadKey`) are already agnostic and JVM+simulator-tested in
`UploadKeys.kt` — but the **orchestration that applies them** (the role-skip, the per-asset loop, the
normalization, the metadata assembly) lives only in the iOS adapter, so it is exercised only on device.
The existing in-memory fake fakes at the *post-mapping* `Resource` level, so it cannot cover that loop.

Move A (`docs/sync-refactor.md §2`) introduces a fakeable **`RawAsset` walk seam** beneath the
enumerator: the iOS side shrinks to a decision-free walk emitting `RawAsset`s carrying only raw facts,
and a **pure `commonMain` mapping** turns `RawAsset`s into engine `Resource`s using the existing
`UploadKeys` functions. That mapping — the fan-out orchestration — then runs on JVM and the simulator
with a fake walk, closing the last untested gap in the discovery path. This is change 3 of three; it is
a `:domain:gallery`-internal refactor.

## What Changes

- **Add raw-fact seam types** in `:domain:gallery` `commonMain`: `RawAsset(assetId, creationDate,
  rawResources)` and `RawResource(type, contentTypeUti, mimeContentType, originalFilename, handle)`.
  `type` is the raw `PHAssetResourceType` value (stable ABI); `handle` is the opaque `PHAssetResource`;
  `assetId` is the **raw** `localIdentifier` (still carrying `/`).
- **Add a decision-free walk seam** `RawAssetSource` (whole-library + by-local-identifiers), with the
  iOS PhotoKit implementation and a **settable in-memory fake**. The walk performs **no** role filter,
  key derivation, or normalization.
- **Extract the fan-out mapping** `RawAsset` → `List<Resource>` into a **pure `commonMain` function**
  that applies `resourceRole` (originals-only skip), `uploadKey`, the `assetId` `'/'→'_'` normalization,
  and the `RESOURCE_META_*` metadata stash. `GalleryResourceEnumerator` becomes the composition
  walk-then-map.
- **`UTType.preferredMIMEType` (UTI→MIME) stays iOS-only** — reimplementing Apple's UTI table is a
  correctness risk — so `mimeContentType` is carried out of the walk as a **raw fact**, never computed
  in `commonMain`.
- **Test the mapping** on JVM + `iosSimulatorArm64` with a fake `RawAssetSource`: role filter, Live-Photo
  fan-out, edit-artifact exclusion, `'/'→'_'`, extension derivation, MIME/creationDate/filename metadata.
- **Shrink `PhotoLibraryResourceEnumerator`** to the decision-free walk (the change-token discovery in
  `IosUploadJobPlatform` is unchanged — it still selects *which* identifiers to walk).
- **Add the `:domain:gallery` note** (`docs/sync-refactor.md §6`) documenting the walk/map split.

## Capabilities

### New Capabilities

_None — the new seam is an internal layer of an existing capability._

### Modified Capabilities

- `gallery-status`: **adds** a decision-free `RawAsset` walk seam (raw PhotoKit facts + iOS-resolved
  MIME/creationDate, opaque handle, no decisions; iOS impl + settable fake) beneath the enumeration
  seam; and **modifies** the "Library resource enumeration seam" so the enumerator composes that walk
  with a **pure `commonMain` mapping** (originals filter, `uploadKey`, `'/'→'_'`, metadata) that is
  JVM+simulator tested — the fan-out orchestration is no longer trapped in the iOS adapter.

## Impact

- **Code (`:domain:gallery`):** new `RawAsset`/`RawResource` + `RawAssetSource` (commonMain), a pure
  `resourcesFrom(rawAssets)` mapping (commonMain), an iOS `RawAssetSource` walk (iosMain, extracted from
  `PhotoLibraryResourceEnumerator`), an `InMemoryRawAssetSource` fake (commonMain/commonTest).
- **Tests:** new `commonTest` for the mapping (JVM + simulator); `PhotoKitSmokeTest` still covers the iOS
  walk glue on the simulator/device.
- **Unchanged contracts:** `UploadJobPlatform.discoverResources`/`Discovery` (the change-token walk in
  `IosUploadJobPlatform` still decides *which* ids to enumerate); `ios-background-upload`'s "Resource
  identity and fan-out" **behavior** (only its execution site relocates to the tested mapping — no
  behavior delta, so no spec change there); `UploadKeys` primitives.
- **Docs:** `docs/design.md`/`docs/sync-refactor.md` `:domain:gallery` note.
- **Depends on:** change 1 (`fix-sync-correctness`) — shares `:domain:gallery` (the `assetIdFromUploadKey`
  parser and the `'/'→'_'` normalization contract this mapping now implements). **Independent of** change
  2 (`relocate-upload-cycle`) — different module — but sequenced last per `docs/sync-refactor.md §6`.
- **Not in scope:** the change-token/change-feed logic, any behavior change, the desktop harness driver.
