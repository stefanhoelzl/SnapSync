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
- [x] 6.4 **Measured on device (SE2, iOS 26.5.2), and the claim holds.** Same library, same answer:

      | | before (eager walk) | after |
      |---|---|---|
      | `N` | 28 | 28 |
      | resource reads, status path | 58 resources / ~52 assets | **0** |
      | elapsed | 320 ms | **26 ms** |

      The cycle's reads track the admitted set, not the fetch: `admitted 8 of 48 candidate(s) → 14
      resource(s)`. A permanent `gallery:` diagnostic reports the read count per walk, because a walk that
      reads everything and one that reads only the admitted differ in nothing observable but elapsed time.
- [x] 6.5 **On-device, `GRANTED`, closed window (ceiling 15 h in the past) holding real in-window photos.**
      The ceiling reaches every consumer: the walk returns 48 candidates, `N=8`, and the deposited
      `device.json` lists 6 assets / 12 resources spanning `2026-07-21T07:46 .. 14:45` — **no post-ceiling
      asset**, against a ceiling of `2026-07-23T19:15`. Resource detail round-trips through the enriched
      ledger row (`live · video/quicktime · IMG_6248.MOV`).

      **Not verified: "status reaches In sync".** `N=8` while the manifest lists 6, because 2 of the 14
      resources hold non-`COMPLETED` ledger rows from earlier runs on this much-reused device. Excluding
      them is what D9 specifies (list only genuinely-uploaded resources), but I did not prove that is the
      cause rather than a backfill miss — it needs a device with a clean ledger.
- [x] 6.6 **On-device, `LIMITED`: the read discipline held.** `gallery: fetched …` — logged only inside
      `PhotoKitCandidateSource.candidates()`, the one autonomous fetch — appears **zero** times across the
      whole session; `SelectionScopedTransfer` intercepted, and the cycle's 45 candidates are the snapshot
      (5 selected + 40 app-created seeds), not a walk.

      **One** limited-access nag appeared, dismissed with "Keep Current Selection" and did not return — so
      not the storm, whose signature is queued alerts draining repeatedly and surviving process death. Most
      likely first-library-touch after a fresh install (which resets iOS's "already asked" state) plus 20
      asset creations. The task's wording ("no alert") was stricter than the probe's actual finding: the
      plist key is documented as not reliably suppressing the nag, and the failure mode is the storm.

## 7. Record

- [x] 7.1 Mark `introduce-eventphotoset` task 4.2 superseded by this change (it proposed threading the policy
      through the seven-layer relay; this removes the relay).
- [x] 7.2 Correct `introduce-eventphotoset`'s `limited-photo-access` delta, which states the snapshot carries
      **facts only** with lazy resources — that describes neither what was built (`candidatesFromResources`,
      eager and held) nor what this change's D5 concludes. It is unarchived, so fix it there rather than
      layer a contradiction over it here.
- [x] 7.3 Update `CLAUDE.md`'s `:domain` module description if the deleted ports are named in it.
