## 1. Honest fakes out of `:test:world` (D6)

- [x] 1.1 Extract `InMemoryUploadDiscovery` into `:adapter:generic:fake`, with its state through the
      constructor and a factory. Rewrite `FakeUploadDiscovery` as a `:test:world` wrapper that keeps the
      `makeWalkUnreadable` lever. World and integration tests stay green.
- [x] 1.2 Extract `InMemoryAlbumManager`, and rewrite `FakeAlbumManager` as a wrapper that keeps `holdAdds`,
      `releaseAdds`, `delete`, `placeIn` and the inspection lists.
- [x] 1.3 Extract `InMemoryPhotoLibraryImporter`, and rewrite `FakePhotoLibraryImporter` as a wrapper that
      keeps `failNextImport`, `failNextImportAfterCreating` and `suspendNextImport`.
- [x] 1.4 Extract `InMemoryPhotoAccess` (status source + requester), and rewrite
      `MutablePhotoAccessStatusSource` as a wrapper.
- [x] 1.5 Confirm `FakeHonestyTest` is green over the four new fakes, and that `./gradlew build` passes.

## 2. Contracts and fake bindings (D1, D9)

- [ ] 2.1 In `:test:contracts`, write `CandidateSourceContract`, `UploadDiscoveryContract`,
      `ImportedAssetPresenceContract` and `PhotoAccessContract`, each with its state vocabulary (`NO_GRANT`
      and the `GRANTED_*` states). Clause addresses (the capture-date window and identifiers) derive from
      the clause id (D5).
- [ ] 2.2 Write `AlbumManagerContract`, plus `PhotoLibraryImporterContract` with its library observation
      handle (outcomes only: exists, capture date).
- [ ] 2.3 Add a fake binding for each of the six contracts in `:adapter:generic:fake` `commonTest`, on
      `currentHost`.
- [ ] 2.4 Fix each fake divergence the fake bindings expose. Expected: `since` ignored, `add` not making
      assets findable by title, `add` to a missing album recorded. Use one commit per divergence, correcting
      the world-backed tests that depended on it in the same commit.

## 3. No-grant bindings on the Kotlin/Native test binary (D3, D4)

- [ ] 3.1 Measure the kexe's photo grant (`currentPhotoPermission()`), and whether album creation and
      imports fail, return, or throw without a grant. Record the readings in the design's Open Questions
      and in `port-contracts`' host matrix.
- [ ] 3.2 Add `IOS_SIM_KEXE` live bindings in `:adapter:ios:ext-safe` `iosTest`:
      - composed `PermissionAwareCandidateSource(PhotoKitCandidateSource, …)`
      - `IosDiscovery`
      - `IosAlbumManager`, for whatever 3.1 measured it reaches

      Reach sets are declared as literals from the measurement. Add the test dependency on `:domain:compose`.
- [ ] 3.3 Add `IOS_SIM_KEXE` live bindings in `:adapter:ios:app-only` `iosTest`: composed
      `PermissionAwareAssetPresence(PhotoKitAssetPresence, …)` and `PhotoLibraryPermission`.
- [ ] 3.4 Commit with `UploadDiscoveryContract`'s no-grant clause red against `IosDiscovery`, and quote the
      outcome table in the commit message. Then, in the next commit, make `IosDiscovery` claim
      `fullEnumeration` only under `GRANTED` (D4). Green.
- [ ] 3.5 Shrink `PhotoKitSmokeTest` to its upload-job fetch test, and point its KDoc at the carved-out
      upload-job phase as its successor. Update `PhotoKitCandidateSource`'s KDoc to stop citing the smoke
      test.

## 4. Simulator-app host and in-app bindings (D2, D3, D7)

- [ ] 4.1 Add `Host.IOS_SIM_APP` with its KDoc, and give `currentHost` a way to tell the simulator app from
      the kexe and the device (for the refusal).
- [ ] 4.2 Add a rig-gated source directory to `:adapter:ios:app-only`, built the way ext-safe's is:
      `iosMain` under `-Psnapsync.rig=true`, `iosTest` otherwise.
- [ ] 4.3 Add `IOS_SIM_APP` live bindings, running under `GRANTED`, in both modules' rig-gated source sets.
      Seeding goes through `PHAssetCreationRequest` from committed fixtures with explicit creation dates.
      No deletes.
- [ ] 4.4 Add the grant precondition: each simulator-app binding refuses the whole run (`CONTRACT_REFUSED`,
      `409`) outside a simulator app holding `GRANTED`.
- [ ] 4.5 Add the simulator-app registry and serve it from the rig: `GET /contract` lists it, and
      `POST /contract/<name>` runs an entry and answers its outcome table.
- [ ] 4.6 Run the registry by hand on a simulator (load `ssh-mac-build` and `ios-simulator`) and triage every
      `Failed` outcome: fix the code or the clause. Decide `ChangeNotSupported` (D9) here.

## 5. CI and the coverage gate (D8)

- [ ] 5.1 Add the `ios-contracts` job to `ios.yml`: rig simulator build, `sim-sign`, a fresh simulator,
      pinned `applesimutils`, launch, and a run of the registry. It fails on `Failed`, a refusal, an empty
      registry, or no answer, attaching a screenshot and the log.
- [ ] 5.2 Extend `ContractCoverageTest`: an `IOS_SIM_APP` live binding counts only if the registry names it,
      and fails naming it otherwise. Add a non-vacuity twin for the registry.
- [ ] 5.3 Push, and confirm `ios-contracts` is green on the PR before `/ship` makes it required.

## 6. Docs and diagrams

- [ ] 6.1 Update CLAUDE.md: the module list (the app-only rig source set, the new fakes, `:test:contracts`
      bindings) and the testing notes (PhotoKit contracts replace the smoke test).
- [ ] 6.2 Update the `rig-channel` skill (the `GET /contract` registry, and simulator-app runs) and the
      `ios-simulator` skill (the contract run, and the no-delete isolation rule).
- [ ] 6.3 Run `./gradlew architectureDiagrams`, commit, and confirm `./gradlew build` is green.
