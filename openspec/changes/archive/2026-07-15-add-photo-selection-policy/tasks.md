## 1. Capability rename (behavior-preserving, no code behavior change)

> The delta model cannot express a capability rename, so the delta was authored under the **old** folder name.
> Base and delta are renamed **together** here, before any behavior lands. Precedent:
> `changes/archive/2026-07-04-add-url-session-upload` task 1.

- [x] 1.1 `git mv openspec/specs/photo-date-cutoff openspec/specs/photo-selection-policy`; update the base spec's title and rewrite its `## Purpose` from "a capture-date cutoff" to the full selection contract (the cutoff is the policy's first rule, not the whole of it)
- [x] 1.2 `git mv` this change's delta folder `specs/photo-date-cutoff` → `specs/photo-selection-policy`; drop the rename banner at its top and update the proposal's Modified-Capabilities key
- [x] 1.3 Update the ~12 `photo-date-cutoff` citations in **live** specs (`bunny-list-endpoint`, `deeplink-config`, `device-manifest`, `gallery-status`, `join-event`). Leave `openspec/changes/archive/**` untouched — it is a historical record
- [x] 1.4 Update the ~60 `photo-date-cutoff` citations in code comments (`grep -rn "photo-date-cutoff" --include=*.kt --include=*.kts`), so no comment cites a capability that no longer exists
- [x] 1.5 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes; `./gradlew build` still green (comment-only change)

## 2. `:domain:gallery` — origin facts on the decision-free walk

- [x] 2.1 Add `mediaSubtypes: Long`, `mediaType: Long`, `pixelWidth: Long`, `pixelHeight: Long`, `hasAdjustments: Boolean` to `RawAsset` as raw facts (KDoc: facts, not decisions — the walk must not itself exclude on any of them)
- [x] 2.2 Populate them in `PhotoLibraryResourceEnumerator.rawAssetsFrom` from the already-fetched `PHAsset` — assert by inspection that **no** new per-asset round-trip is introduced (all five are in-memory properties; the expensive `assetResourcesForAsset` call is untouched)
- [x] 2.3 Mirror the new fields in `InMemoryRawAssetSource` / the `RawAsset` test builders so the JVM + simulator drive the mapping
- [x] 2.4 Extend `fetchOptionsSince`'s predicate via `NSCompoundPredicate`: the existing widened `creationDate` bound AND the subtype exclusion, written **`NOT ((mediaSubtypes & N) != 0)`** — never the `== 0` form (returns zero rows), never arithmetic, never `hasAdjustments` (both abort the process). Add a KDoc note recording that these are device-verified constraints, not preferences
- [x] 2.5 `RawAssetMappingTest`: origin facts survive the mapping unchanged; a screenshot `RawAsset` is **not** dropped by the walk (the walk stays decision-free)
- [x] 2.6 `./gradlew compileIosMainKotlinMetadata` green (the Linux-runnable iOS proxy)

## 3. `:capability:album` — decision-free membership seam + the `commonMain` denylist

- [x] 3.1 Add a decision-free verb to `AlbumManager`: takes album titles + the cutoff, returns member asset ids. Cost O(albums), **not** a per-asset membership test
- [x] 3.2 Implement it in `IosAlbumManager` (`PHAssetCollection.fetchAssetCollectionsWithType` for user albums; bound the member fetch by the cutoff predicate). Match user albums by **title**; any smart album by **subtype** — never by title, which is system-localized
- [x] 3.3 Add the denylist policy as a `commonMain` constant (case-insensitive exact match): WhatsApp, Telegram, Signal, Threema, Viber, WeChat, LINE, Discord, Instagram, Facebook, Messenger, Snapchat, TikTok, Twitter, X, Pinterest, Reddit
- [x] 3.4 Tests in `commonTest`: exact + case-insensitive match; `WhatsApp Backup` does **not** match `WhatsApp`; a non-denylisted user album admits its members

## 4. `:capability:upload` — the authoritative selection filter (the heart of the change)

- [x] 4.1 Add the origin-exclusion rules as a pure `commonMain` predicate over `RawAsset`/`Resource` facts: screenshot bit (`1<<2`), screen-recording bit (`1<<19`), primary-resource MIME `image/gif`, image area < 3 MP, video area < 1280×720 — the two floors **skipped when `hasAdjustments`**
- [x] 4.2 Add the injected album-exclusion port to `UploadCycle` (a `suspend () -> Set<String>`, beside the existing `suppressedAssetIds`), wired to the album coordinator at the composition roots
- [x] 4.3 Apply the origin filter in `UploadCycle` before the engine and before `retainAssets`, alongside the cutoff filter and echo-suppression
- [x] 4.4 **Feed `onDiscovery` the origin-filtered set** (today it is handed the raw `discovery`). The cutoff filter stays *after* the hook — origin exclusions are event-independent, the cutoff is per-event (see design). This is the fix that stops an excluded photo leaking into `device.json` and the event union
- [x] 4.5 `UploadCycleTest`: one scenario per exclusion rule × {full enumeration, incremental walk}; a 1080p video is **admitted**; a sub-floor **edited** photo is admitted; interaction with the cutoff and with echo-suppression; an excluded asset creates no upload job, gains no ledger row, and does **not** reach `onDiscovery`

## 5. `:domain:status` — the same policy, or the screen never reaches 100%

- [x] 5.1 Apply the identical origin filter + album exclusion in `OwnDeviceGalleryStatusSource.refresh` (it enumerates independently of `UploadCycle`, so the identity is a requirement, not a coincidence)
- [x] 5.2 `OwnDeviceGalleryStatusSourceTest`: a post-cutoff screenshot does not inflate `N`, so the joined screen reaches "in sync" rather than pegging below 100% forever

## 6. Wiring + harnesses + integration

- [x] 6.1 Compose the album-exclusion port at **both** composition roots — `UploadExtensionRoot` (PhotoKit ≥26.1) and `SnapSyncRoot`/`UrlSessionUploadController` (app-driven 18–26.0) — so the policy holds on both tiers
- [x] 6.2 `:test:world`: extend the world's raw-asset fakes with the origin facts, and add inspector levers to forge an excluded asset (screenshot / low-res / GIF / denylisted-album member)
- [x] 6.3 Both desktop harnesses: surface those levers so every exclusion is reviewable without a device (`:app:desktop:run` world inspector; `:app:desktop:ui:run` forge)
- [x] 6.4 `:test:integration`: an excluded photo neither uploads (no object lands, no `COMPLETED` row) **nor** appears in the device manifest — asserted over the real stack against `:test:world`
- [x] 6.5 `./gradlew build` + `compileIosMainKotlinMetadata` green

## 7. Verify on device, then close out

- [x] 7.1 Verified on the SE2 (iOS 26.5.2). `SNAPSYNC_SEED_POLICY=20` seeds assets straddling the 3 MP floor; the real enumerator + real policy then reported: `enumerated 45 resource(s) … 35 origin-excluded → N=10` — exactly the 10 above-floor assets admitted, the 10 below-floor ones plus 25 legacy 64x64 seeds excluded. **The predicate returns a superset, not zero**: had the `== 0` form shipped, this line would read `enumerated 0` and the library would silently empty. Floor and `N` both correct on device
- [x] 7.2 Verified on the SE2 with a **real, OS-generated screen recording** (added by the operator; carries the `videoScreenRecording` bit `1<<19`, the same subtype-mask mechanism as `photoScreenshot`). Subtype census: `library total=46, screen-recordings=1` — the `(mediaSubtypes & 524288) != 0` **select** form matches a real asset, the thing a synthesized library cannot show (`PHAssetCreationRequest` cannot set a subtype). The production **exclusion** predicate then dropped exactly it: `enumerated 45` = 46−1. Both directions of the subtype rule confirmed on device; screenshots share the identical mechanism
- [x] 7.3 Update root `CLAUDE.md`: the module table (`:capability:album` gains the membership seam) and the capability rename
- [x] 7.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`; archive the change
