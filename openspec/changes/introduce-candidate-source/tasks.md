## 1. Remove the GIF exclusion (unblocks everything else)

- [x] 1.1 Delete `SelectionRule.ExcludeGif` and its `SelectionPolicy.from` entry; delete `MIME_GIF` and the
      `isGif` field from `AssetFacts`, `RawAsset.toFacts()` and `factsFromResources`.
- [x] 1.2 `commonTest`: delete the GIF exclusion cases in `SelectionPolicyTest`; add the replacement — an
      ordinary GIF (0.13 MP, unedited) is still excluded, **by the image floor**, so the removal is not
      mistaken for "GIFs now upload freely".
- [x] 1.3 `commonTest`: pin the property the removal buys — a facts-only admission and a
      resources-carrying admission over the same library yield the **identical** set. This is the
      preview-vs-`N` divergence, stated as a test.

## 2. The candidate source seam

- [x] 2.1 Define `ports/CandidateSource` with the single method `candidates(policy): List<Candidate>`.
      `Candidate` (facts + `suspend resources()`) already exists in `model/` from `introduce-eventphotoset`.
- [x] 2.2 `EventPhotoSet` takes a `CandidateSource` instead of a lambda; delete `candidatesFromFacts` /
      `candidatesFromResources` as *public* seams where a real source now serves, keeping whichever remain
      genuinely "already fetched" (the ledger-backed manifest projection, the LIMITED snapshot).
- [x] 2.3 Delete `ports/RawAssetSource`, `ports/PhotoLibrary` and `compose/ResourceEnumerator`. Keep
      `resourcesFrom`, `uploadKey`, `resourceRole`, `normalizeAssetId` in `model/`, now called per-asset.
- [x] 2.4 `BackgroundTransfer.discoverResources(sinceToken, policy)` — replace `since: String`; `Discovery`
      carries candidates. Update `SelectionScopedTransfer` (behavior unchanged — it still reports
      `fullEnumeration = false`, which is what stops `retainAssets` pruning to the selection).
- [x] 2.5 Confirm no `since: String` parameter survives anywhere in the read path (grep gate, then delete
      the grep — the ports are gone, so there is nothing left to regress).

## 3. iOS adapter: translate the rules

- [x] 3.1 `PhotoLibraryRawAssetSource` becomes the iOS `CandidateSource`: fetch → `toAssetFacts` → candidates
      that close over the `PHAsset` (never over a bare id — a candidate that re-fetched by identifier at
      read time would reintroduce the measured off-flow-fetch storm).
- [x] 3.2 Build `PHFetchOptions` by `when`-matching `SelectionRule`: translate `CaptureAfter`,
      `CaptureBefore`, `ExcludeScreenshots`, `ExcludeScreenRecordings`; explicitly ignore `MinImageArea`,
      `MinVideoArea`, `NotEcho`, `NotInDenylistedAlbum` with the reason at each branch. The three
      device-verified predicate constraints are unchanged — `NOT ((mediaSubtypes & N) != 0)`, no arithmetic,
      no `hasAdjustments`.
- [x] 3.3 `Candidate.resources()` reads `assetResourcesForAsset` for that asset and maps through the shared
      `resourcesFrom`.
- [x] 3.4 `IosDiscovery` builds candidates from its own full or id-scoped fetch via one shared internal
      mapping; the id-scoped variant stays internal to it (nothing else has identifiers to scope by).
- [x] 3.5 `iosSimulatorArm64Test`: the exhaustive `when` is the point of the translation — assert every rule
      is either translated or explicitly declined, so a new rule cannot be silently dropped.

## 4. Permission-aware source; consumers stop branching

- [x] 4.1 `SnapshotCandidateSource` over the existing selection cell. **Read discipline unchanged**: the
      snapshot is still read eagerly, with resources, at the cold-launch baseline and observer emissions.
- [x] 4.2 Assemble the permission-aware `CandidateSource` in `compose/` (full walk under `GRANTED`, snapshot
      under `LIMITED`).
- [x] 4.3 Delete `OwnDeviceGalleryStatusSource.refreshFrom`; `refresh(policy)` serves both grants.
- [x] 4.4 `ShareableCountSource` takes a `CandidateSource`; delete its `GRANTED`/`LIMITED` arm. **Keep** the
      `DENIED`/`NOT_DETERMINED` → `null`, which is a different question (render no row, not zero).
- [x] 4.5 `commonTest`: `N` and the preview agree under `GRANTED` and under `LIMITED`; a `DENIED` grant still
      yields no count rather than zero.

## 5. Fakes and harness

- [x] 5.1 `InMemoryRawAssetSource` + `InMemoryPhotoLibrary` collapse into one honest in-memory
      `CandidateSource` (state cell constructor-injected; `FakeHonestyTest` still applies).
- [x] 5.2 `:test:world` gallery lever and `WorldInspectorController`'s policy badge read the new seam.
- [x] 5.3 `:test:integration`: `AdmittedSetIntegrationTest` still passes unchanged — it asserts the property
      over the composed core, so it should not need to know the seam moved. If it does need changing, that
      is a signal the seam leaked into the assertion.

## 6. Verification

- [x] 6.1 `./gradlew build` green (all targets + JVM tests, incl. `:test:architecture`).
- [x] 6.2 `./gradlew compileIosMainKotlinMetadata` green.
- [x] 6.3 `./gradlew architectureDiagrams` — the module graph is unchanged, but ports moved; commit if it
      drifts.
- [ ] 6.4 Measure what the change is for: log the resource-read count for one walk before and after on a
      library holding sub-floor images. The claim is that reads drop from *every date-passing asset* to
      *admitted assets only* — if it does not, the seam moved but the cost did not.
- [ ] 6.5 On-device, `GRANTED`: re-run decouple's closed-window scenario (a post-ceiling photo present);
      status reaches "In sync" and the manifest lists no post-ceiling asset.
- [ ] 6.6 On-device, `LIMITED`: join with a selection, confirm the sanctioned reads still fire and **no**
      limited-access alert appears — during the run and on a bare home screen after `SIGKILL`, which is how
      the archived probe's queued alerts surfaced.

## 7. Record

- [x] 7.1 Mark `introduce-eventphotoset` task 4.2 superseded by this change (it proposed threading the policy
      through the seven-layer relay; this removes the relay).
- [x] 7.2 Correct `introduce-eventphotoset`'s `limited-photo-access` delta, which states the snapshot carries
      **facts only** with lazy resources — that describes neither what was built (`candidatesFromResources`,
      eager and held) nor what this change's D5 concludes. It is unarchived, so fix it there rather than
      layer a contradiction over it here.
- [x] 7.3 Update `CLAUDE.md`'s `:domain` module description if the deleted ports are named in it.
