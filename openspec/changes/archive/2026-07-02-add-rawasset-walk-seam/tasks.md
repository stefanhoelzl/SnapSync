# Tasks — add-rawasset-walk-seam (Move A)

Behavior-preserving `:domain:gallery` refactor that adds a fakeable `RawAsset` walk seam beneath the
enumerator and extracts the fan-out mapping into a pure, JVM-tested `commonMain` function. Depends on
change 1 (shares `:domain:gallery`); independent of change 2. The produced `Resource`s SHALL be
byte-identical to today's — the tests are the proof.

## 1. Raw-fact seam types (`:domain:gallery` commonMain)

- [x] 1.1 Add `RawResource(type: Long, contentTypeUti: String, mimeContentType: String, originalFilename: String, handle: Any)` — raw `PHAssetResourceType` value, iOS-resolved MIME, opaque `PHAssetResource` handle
- [x] 1.2 Add `RawAsset(assetId: String /* raw localIdentifier, with '/' */, creationDate: String, rawResources: List<RawResource>)`
- [x] 1.3 Add the `RawAssetSource` interface: `suspend fun walkAll(): List<RawAsset>` and `suspend fun walk(localIdentifiers: List<String>): List<RawAsset>` — decision-free (no role filter, key derivation, or normalization)

## 2. Pure mapping (`:domain:gallery` commonMain)

- [x] 2.1 Add `resourcesFrom(rawAssets: List<RawAsset>): List<Resource>` — normalize `assetId` `'/'→'_'`; per `RawResource`, `resourceRole(type) ?: skip`; build `Resource(filename = uploadKey(assetId, role, originalFilename), assetId, contentType = contentTypeUti, metadata = {RESOURCE_META_CREATION_DATE, RESOURCE_META_ORIGINAL_FILENAME, RESOURCE_META_MIME}, data = handle)`
- [x] 2.2 Re-implement `GalleryResourceEnumerator` as the walk→map composition over an injected `RawAssetSource` (`enumerate() = resourcesFrom(source.walkAll())`, `resources(ids) = resourcesFrom(source.walk(ids))`)
- [x] 2.3 Add `InMemoryRawAssetSource` (settable) for the harness/tests

## 3. Mapping tests (JVM + simulator)

- [x] 3.1 `commonTest`: originals-only skip (drop edit-artifact/proxy/RAW-alternate raw types), Live-Photo fan-out (`primary` + `live`), `'/'→'_'` normalization, extension derivation, and `creationDate`/originalFilename/MIME metadata — asserted field-by-field against the known-good `Resource` shape
- [x] 3.2 Confirm the round-trip with change 1's `assetIdFromUploadKey`: a mapped `filename`'s parsed `assetId` equals the normalized input `assetId`
- [x] 3.3 `./gradlew :domain:gallery:jvmTest` green (the coverage win — fan-out orchestration now on JVM)

## 4. Shrink the iOS walk (iosMain)

- [x] 4.1 Extract the PhotoKit walk from `PhotoLibraryResourceEnumerator.resourcesForAssets` into an iOS `RawAssetSource` impl: fetch `PHAsset`s, read raw `localIdentifier`/`creationDate`, walk `PHAssetResource`, emit `RawResource`s carrying raw `type`, `uniformTypeIdentifier`, original filename, and `UTType.preferredMIMEType` (fallback `application/octet-stream`) — no role filter, no `uploadKey`, no normalization
- [x] 4.2 Wire the iOS `GalleryResourceEnumerator` to compose the iOS `RawAssetSource` with the shared `resourcesFrom` mapping
- [x] 4.3 Keep `UTType`→MIME strictly iOS-side (a raw fact); `commonMain` never sees a `UTType`
- [x] 4.4 Confirm `IosUploadJobPlatform.discoverResources` (change-token full/incremental selection) is unchanged and still delegates to the enumerator

## 5. Build & verify (no behavior change)

- [x] 5.1 `./gradlew build` green — includes the new gallery mapping `commonTest` on JVM
- [x] 5.2 `./gradlew compileIosMainKotlinMetadata` green (iOS proxy compile of the extracted walk)
- [x] 5.3 Confirm `PhotoKitSmokeTest` still covers the iOS walk glue on the simulator (macOS CI)
- [x] 5.4 Decide `InMemoryGalleryResourceEnumerator`'s fate (retire vs keep) by consumer count; update callers
- [x] 5.5 Diff review: `Resource` output is byte-identical to pre-change (filename, assetId, contentType, metadata, data)

## 6. Docs & validation

- [x] 6.1 Add the `:domain:gallery` walk/map-split note to `docs/design.md` (and `docs/sync-refactor.md §6` if kept)
- [x] 6.2 `openspec validate add-rawasset-walk-seam --strict` passes

## 7. On-device confirmation

- [x] 7.1 On-device (SE2, iOS 26.5) from this working tree: dev **ARCHIVE SUCCEEDED** (extracted PhotoKit walk links into both frameworks), IPA installs & launches clean, **no crash report**. Decisive Move-A check: the app-side status runs the extracted `walkAll → resourcesFrom` to derive expected filenames and matches them against the stored bunny objects — it reads **"9 images synced", identical to pre-Move-A** builds, so the extracted walk produces **byte-identical keys against real photos** (a drifted walk would drop the count). Extension-process invocation was not caught in the syslog window (OS-owned `process()` timing) but shares the same walk code path and generated no crash
