## 0. Preconditions (external gate)

- [ ] 0.1 Confirm `decouple-event-window-from-lifetime` has shipped and every device has reconciled — its
      backfill fills the capture-date ceiling that this change's strict decode (task 6.x) will require
      (design D7a). Do NOT merge the ceiling-required tasks until this holds.

## 1. Global value-class dates (foundation, behavior-preserving)

- [x] 1.1 Add `CaptureDate` (`@JvmInline value class` over the canonical `…Z` string, `Comparable` by the
      string) in `:domain` `model/`, with a transparent serializer emitting the raw string (wire + persisted
      config byte-identical).
- [x] 1.2 Add the distinct role types — `CaptureCutoff`, `CaptureCeiling`, `EventStart`, `EventEnd`,
      `DeletesAt` — each wrapping `CaptureDate`; `createdAt` gets its own type (it carries milliseconds, not
      the canonical shape).
- [x] 1.3 Thread the role types through `EventConfig`, `JoinLoad`/`EventDetails`, the `Cutoff` clamps, and
      the pickers; keep serialization emitting `…Z` so nothing on the wire or in storage changes.
- [x] 1.4 `commonTest`: string-ordering preserved (lexicographic == chronological), round-trip serialization,
      and that a role type cannot be constructed from another role's value (compile-fenced by type).

## 2. One-policy admission over typed dates (fixes the ceiling bug)

- [x] 2.1 Introduce `SelectionPolicy` (sealed `None | Admitting(rules: List<SelectionRule>)`) in `model/`;
      `SelectionRule` sealed: `CaptureAfter(CaptureCutoff)`, `CaptureBefore(CaptureCeiling)`,
      `ExcludeScreenshots`, `ExcludeScreenRecordings`, `MinImageArea`, `MinVideoArea`, `ExcludeGif`,
      `ExcludeEdited`, `NotEcho(Set)`, `NotInDenylistedAlbum(Set)`. `admits(asset) = rules.all { it.admits }`.
- [x] 2.2 `SelectionPolicy.from(config, suppressed, albumExcluded)` — the ONE derivation, reading
      `config.minPhotoDate`/`config.maxPhotoDate` by name into typed bounds; delete `Contribution` and fold
      `None`/`Since` into `None`/`Admitting`.
- [x] 2.3 Route the four consumers through the single `admits`: `UploadCycle` byte filter, the device-manifest
      projection, `OwnDeviceGalleryStatusSource` (both `refresh` and `refreshFrom`), and `ShareableCountSource`
      — all deriving the admitted set, none re-stating a rule. This is the fix: the ceiling now applies at
      the manifest and `N`.
- [x] 2.4 Add a `:test:architecture` guard forbidding a capture-date comparison (`creationDate` against a
      bound) anywhere but inside `SelectionPolicy` — the drift-class guard.
- [x] 2.5 `commonTest`: a post-ceiling asset is admitted by NONE of upload/manifest/`N`/preview; add a
      closed-window fixture (a photo after `until`) — the exact task-11.1 shape the old fixtures missed.

## 3. Neutral fact vocabulary

- [x] 3.1 Define neutral `AssetFacts` (`isScreenshot`, `isScreenRecording`, `isVideo`, `imageArea`,
      `videoArea`, `isEdited`, `isGif`, `creationDate`) in `model/`; the selection rules read only these.
- [x] 3.2 Move the `PHAsset` → `AssetFacts` interpretation (the `mediaSubtypes`/`mediaType` bit logic + pinned
      constants) into `:adapter:ios` (`iosMain`); `model/` no longer references a PhotoKit bitmask.
- [x] 3.3 `InMemoryRawAssetSource` (fake) produces neutral `AssetFacts` directly; policy `commonTest` uses
      `isScreenshot = true` etc. (no hand-built bitmask). `iosSimulatorArm64Test` covers the interpretation.
- [x] 3.4 Extend the `:test:architecture` guard (or add one) forbidding PhotoKit media-subtype/type ABI in
      `:domain`.

## 4. EventPhotoSet + CandidateSource

- [x] 4.1 Define `EventPhotoSet { count(); assets() }` and `Asset { assetId; creationDate; resources() }` in
      `:domain`; admission applied at query, over an injected `CandidateSource`.
- [~] 4.2 Candidate backings: `candidatesFromResources` (cycle-side + LIMITED snapshot) and
      `candidatesFromFacts` (app-side facts walk) are in place, and the native narrowing is platform-owned
      (§3 moved `EXCLUDED_SUBTYPE_MASK` into the iOS adapter alongside the predicate that inlines it). The
      lower-bound push remains a source contract, honoured as today. **Not done:** having the iOS source
      `when`-translate the sealed `SelectionRule` set instead of hardcoding the mask + a cutoff string —
      a refinement of an optimization layer that can neither widen nor narrow the admitted set, so it is
      separable from the correctness work above.
- [x] 4.3 `Asset.resources()`: ONE lazy-fetch path for both grants — the device spike (task 7.1, DONE)
      measured zero alerts from off-flow `assetResourcesForAsset` on held refs under `.limited`. The LIMITED
      `Snapshot` source therefore carries **facts only** (cheaper capture), not pre-captured resources. Keep
      the backing behind the `Asset.resources()` seam so reverting to pre-capture is a one-impl change if a
      storm ever appears.
- [x] 4.4 Reduce the four consumers to `EventPhotoSet` calls: upload `assets().flatMap { it.resources() }`,
      `N`/preview `count()`, manifest via the ledger view (§5). Per-process impls (design D4): cycle-side vs
      app-side.
- [x] 4.5 `commonTest` + `:test:integration`: the admitted set is identical across consumers over `:test:world`.

## 5. Manifest from the enriched ledger (eliminate the accumulator)

- [x] 5.1 Add `creationDate`/`role`/`contentType`/`filename` to the ledger row (SQLDelight schema + a
      migration `.sqm`); update the store contract.
- [x] 5.2 Rewrite `DeviceManifestProducer` to project the manifest from the ledger's COMPLETED rows,
      date-filtered to the current event window; delete the durable accumulator + its store.
- [x] 5.3 Union byte-presence check unchanged (no code change). It is now genuinely defense-in-depth:
      the manifest lists only COMPLETED resources, so a named byte is present by construction and the
      check catches only a residual COMPLETED-but-absent edge.
- [x] 5.4 `commonTest`: manifest lists exactly the in-window COMPLETED resources; a deleted asset drops; a
      not-yet-uploaded asset is absent until COMPLETED.

## 6. Ceiling required (LAST — gated on §0)

- [ ] 6.1 Make `EventConfig.maxPhotoDate` (the ceiling) required; a config lacking it fails to decode. Remove
      the unbounded-ceiling default and the `CaptureCeiling.Unbounded`-equivalent everywhere.
- [ ] 6.2 Remove the reconcile's absent-ceiling backfill / unbounded-until-backfilled branch.
- [ ] 6.3 Update `EventConfig` decode tests: a pre-ceiling blob reads as no config (the deliberate reversal);
      a current config decodes with a concrete ceiling.

## 7. Device spike — DONE

- [x] 7.1 Device-verify (SE2 iOS 26.5.2, `.limited`, plist suppression ON): 6 off-flow bursts of
      `assetResourcesForAsset` on held baseline refs (54 reads) produced **zero** alerts — clean during the
      bursts and a bare home screen 12 s/32 s after SIGKILL. The probe's storm is specific to library
      **fetches**, not resource reads. ⟹ task 4.3 collapses to one lazy path; LIMITED snapshot = facts only.
      Caveats recorded in design D10 (per-round timing unlogged → possible cache hits; foreground only).

## 8. Verification

- [x] 8.1 `./gradlew build` (all targets + JVM tests, incl. `:test:architecture`) green.
- [x] 8.2 `./gradlew compileIosMainKotlinMetadata` green.
- [x] 8.3 `deno test` (api) green — 219 passed; the manifest wire shape is unchanged — no backend change expected; confirms the manifest wire shape is unchanged.
- [x] 8.4 `./gradlew architectureDiagrams` run — no drift (the module graph did not move; every change was within `:domain` packages and existing adapters).
- [ ] 8.5 On-device re-run of decouple's task 11.1 scenario: join a closed-window event with a post-ceiling
      photo; confirm the status reaches "In sync" and the manifest lists no post-ceiling asset.
