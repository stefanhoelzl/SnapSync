# Tasks — add-rawasset-walk-seam (Move A)

Behavior-preserving `:domain:gallery` refactor that adds a fakeable `RawAsset` walk seam beneath the
enumerator and extracts the fan-out mapping into a pure, JVM-tested `commonMain` function. Depends on
change 1 (shares `:domain:gallery`); independent of change 2. The produced `Resource`s SHALL be
byte-identical to today's — the tests are the proof.

## 1. Raw-fact seam types (`:domain:gallery` commonMain)

- [ ] 1.1 Add `RawResource(type: Long, contentTypeUti: String, mimeContentType: String, originalFilename: String, handle: Any)` — raw `PHAssetResourceType` value, iOS-resolved MIME, opaque `PHAssetResource` handle
- [ ] 1.2 Add `RawAsset(assetId: String /* raw localIdentifier, with '/' */, creationDate: String, rawResources: List<RawResource>)`
- [ ] 1.3 Add the `RawAssetSource` interface: `suspend fun walkAll(): List<RawAsset>` and `suspend fun walk(localIdentifiers: List<String>): List<RawAsset>` — decision-free (no role filter, key derivation, or normalization)

## 2. Pure mapping (`:domain:gallery` commonMain)

- [ ] 2.1 Add `resourcesFrom(rawAssets: List<RawAsset>): List<Resource>` — normalize `assetId` `'/'→'_'`; per `RawResource`, `resourceRole(type) ?: skip`; build `Resource(filename = uploadKey(assetId, role, originalFilename), assetId, contentType = contentTypeUti, metadata = {RESOURCE_META_CREATION_DATE, RESOURCE_META_ORIGINAL_FILENAME, RESOURCE_META_MIME}, data = handle)`
- [ ] 2.2 Re-implement `GalleryResourceEnumerator` as the walk→map composition over an injected `RawAssetSource` (`enumerate() = resourcesFrom(source.walkAll())`, `resources(ids) = resourcesFrom(source.walk(ids))`)
- [ ] 2.3 Add `InMemoryRawAssetSource` (settable) for the harness/tests

## 3. Mapping tests (JVM + simulator)

- [ ] 3.1 `commonTest`: originals-only skip (drop edit-artifact/proxy/RAW-alternate raw types), Live-Photo fan-out (`primary` + `live`), `'/'→'_'` normalization, extension derivation, and `creationDate`/originalFilename/MIME metadata — asserted field-by-field against the known-good `Resource` shape
- [ ] 3.2 Confirm the round-trip with change 1's `assetIdFromUploadKey`: a mapped `filename`'s parsed `assetId` equals the normalized input `assetId`
- [ ] 3.3 `./gradlew :domain:gallery:jvmTest` green (the coverage win — fan-out orchestration now on JVM)

## 4. Shrink the iOS walk (iosMain)

- [ ] 4.1 Extract the PhotoKit walk from `PhotoLibraryResourceEnumerator.resourcesForAssets` into an iOS `RawAssetSource` impl: fetch `PHAsset`s, read raw `localIdentifier`/`creationDate`, walk `PHAssetResource`, emit `RawResource`s carrying raw `type`, `uniformTypeIdentifier`, original filename, and `UTType.preferredMIMEType` (fallback `application/octet-stream`) — no role filter, no `uploadKey`, no normalization
- [ ] 4.2 Wire the iOS `GalleryResourceEnumerator` to compose the iOS `RawAssetSource` with the shared `resourcesFrom` mapping
- [ ] 4.3 Keep `UTType`→MIME strictly iOS-side (a raw fact); `commonMain` never sees a `UTType`
- [ ] 4.4 Confirm `IosUploadJobPlatform.discoverResources` (change-token full/incremental selection) is unchanged and still delegates to the enumerator

## 5. Build & verify (no behavior change)

- [ ] 5.1 `./gradlew build` green — includes the new gallery mapping `commonTest` on JVM
- [ ] 5.2 `./gradlew compileIosMainKotlinMetadata` green (iOS proxy compile of the extracted walk)
- [ ] 5.3 Confirm `PhotoKitSmokeTest` still covers the iOS walk glue on the simulator (macOS CI)
- [ ] 5.4 Decide `InMemoryGalleryResourceEnumerator`'s fate (retire vs keep) by consumer count; update callers
- [ ] 5.5 Diff review: `Resource` output is byte-identical to pre-change (filename, assetId, contentType, metadata, data)

## 6. Docs & validation

- [ ] 6.1 Add the `:domain:gallery` walk/map-split note to `docs/design.md` (and `docs/sync-refactor.md §6` if kept)
- [ ] 6.2 `openspec validate add-rawasset-walk-seam --strict` passes

## 7. On-device confirmation

- [ ] 7.1 One dev-IPA build + install + re-provision against a fresh event id confirms uploads still land in the bunny storage zone with identical keys — proves the walk/map split preserved runtime behavior
