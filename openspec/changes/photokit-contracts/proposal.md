## Why

PhotoKit is the external system SnapSync depends on most, and it is the least verified. The only test that
calls real PhotoKit is `PhotoKitSmokeTest`, which runs without photo access and asserts that three calls
return rather than trap. Every integration test and both desktop harnesses run on in-memory doubles of the
photo library, and nothing checks those doubles against the real thing. Several port obligations exist only
in doc comments. Reading the code shows at least one real adapter that breaks its port's documented contract
as soon as it runs without a grant. This is phase 6 of the testing-concept sequence: it runs the mechanism
`port-contracts` established on `SecureStore` against the ports backed by the photo library.

## What Changes

- **Six port contracts** in `:test:contracts`, hand-written as clause values:
  - `CandidateSource`
  - `UploadDiscovery`
  - `ImportedAssetPresence`
  - `PhotoAccess`: `PhotoAccessStatusSource` together with `PhotoAccessRequester.request()`
  - `AlbumManager`
  - `PhotoLibraryImporter`

  Each is bound by an honest fake in `:adapter:generic:fake` and by the real iOS adapter.
- **A new host, `IOS_SIM_APP`**: the rig build of the app, ad-hoc signed, on a simulator. Photo access is
  granted before launch with `applesimutils`. This is the host where most of PhotoKit can be reached (reads
  under a full grant, asset creation, albums, imports). CI runs it **live**, so it is not recorded and
  needs no PhotoKit recording seam. The simulator's Kotlin/Native test binary (`IOS_SIM_KEXE`) covers the
  no-grant state.
- **A new CI job, `ios-contracts`.** On every push it builds the rig app for the simulator, grants photo
  access, launches the app, and runs every contract registered for `IOS_SIM_APP` over the rig channel.
  The job fails on any `Failed` outcome. It becomes a third iOS merge gate.
- **The live binding is what production composes.** Two production compositions wrap a PhotoKit adapter in
  a grant-aware layer that owns part of the port's contract:
  - `PermissionAwareCandidateSource`
  - `PermissionAwareAssetPresence`

  Without a grant, the bare adapters answer as though the library were empty: a counted zero, or
  `ABSENT` for every id. That is the collapse behind `SNAPSYNC-14`/`16`. The contract binds the composition, because the composition is what
  production calls.
- **`IosDiscovery` claims an authoritative walk only under a full grant.** Read from the code: without a
  grant, PhotoKit returns an empty fetch, and the adapter reports it as a full enumeration. Under the
  cycle's presence diff, that is a walk that deletes every in-window row. Both cycles are gated on a
  grant, so no shipped path reaches it today. The contract's first run on the no-grant host is expected
  to fail here. The fix lands after that red run, so the failure is on record.
- **Honest fakes are extracted from `:test:world`.** `:test:world` keeps the levers as wrappers:
  - `InMemoryUploadDiscovery` (from `FakeUploadDiscovery`)
  - `InMemoryAlbumManager` (from `FakeAlbumManager`)
  - `InMemoryPhotoLibraryImporter` (from `FakePhotoLibraryImporter`)
  - `InMemoryPhotoAccess` (from `MutablePhotoAccessStatusSource`)
- **`PhotoKitSmokeTest` is retired, apart from its upload-job test.** Its authorization and asset-fetch
  checks become contract clauses on `IOS_SIM_KEXE`. The upload-job fetch test stays until the upload-job
  tier gets its own phase (below).
- **Stated exclusions.** Each item is excluded for a reason, and none has a fake-only clause.
  - **`BackgroundTransfer` (PhotoKit tier), `SimulatorUploadJobQueue`, `UploadExtensionRegistry`: carved
    out as their own phase.** The upload-job subsystem runs in the device extension process, a host
    `port-contracts` lists as unbound and unmeasured. On a simulator, creating a job terminates the
    process, and on a device, creating one starts a real upload.
  - **Everything under a partial (`LIMITED`) grant, and `PhotoSelectionChangeSource`.** A simulator cannot
    enter `LIMITED`. A device can enter it only by a human action in Settings, and one recording per host
    cannot hold two grant states. The platform facts stay in `limited-photo-access`, with their
    measurements.
  - **`PhotoAccessRequester.openSettings()` / `choosePhotos()`.** Each hands control to another surface,
    and its outcome exists only after a human decides.
  - **The world's failure levers.** The thrown enumeration, the import that fails after creating, and the
    suspended import that `SNAPSYNC-9` lives in stay fake-backed tests of the project's own logic.
  - **`GalleryStatusSource`.** Its real implementation is `:domain` feature code, not a PhotoKit adapter.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `port-contracts`:
  - The host matrix gains `IOS_SIM_APP` and the measured photo-access column.
  - A photo grant the process cannot give itself is a precondition of a run, not a state a binding enters.
  - A live binding binds the composition production calls where that composition owns part of the
    port's contract.
  - Hosts CI can reach in-app are run live over the rig, not recorded.
- `architecture-guards`: the contract-coverage gate counts an `IOS_SIM_APP` live binding only through
  the in-app registry the CI job runs.
- `ios-ci`: the in-app contract job; the merge gates become three parallel jobs.
- `module-architecture`: `:test:contracts` is also linked into the rig-gated source set of the app-only
  adapter module (the module-set law names only the extension-safe one).
- `testing-architecture`: the simulator smoke tests no longer mark where the testable PhotoKit surface
  stops; the contracts' declared reach does.
- `harness-world-model`: the world's photo-library doubles are wrappers over honest fakes in
  `:adapter:generic:fake`, not levered fakes of their own.

## Impact

- **New:**
  - The contracts above, plus their state vocabularies (`:test:contracts`).
  - The four honest fakes (`:adapter:generic:fake`).
  - Bindings in `:adapter:generic:fake` `commonTest`, `:adapter:ios:ext-safe` and `:adapter:ios:app-only`
    (`iosTest` for `IOS_SIM_KEXE`, the rig-gated source set for `IOS_SIM_APP`).
  - A rig-gated source set in `:adapter:ios:app-only`.
  - The `ios-contracts` job, with seed fixtures (a valid JPEG and an invalid resource file).
- **Changed:**
  - `IosDiscovery`: its authority requires a full grant.
  - `:test:world`: its photo doubles become wrappers.
  - `PhotoKitSmokeTest`: shrinks to the upload-job test.
  - `Host`: gains `IOS_SIM_APP`.
  - The rig's `/contract` verb: it serves the simulator-app registry.
  - `ContractCoverageTest`.
- **CI:** one more macOS job per push, which takes a merge-gate context.
- **Docs:** CLAUDE.md's module list and testing notes; the `rig-channel` and `ios-simulator` skills.
- **Coordination:** phase 5 (`app-group-contracts`) needs a simulator-app host too. `IOS_SIM_APP` is
  defined here once, and phase 5 should bind to it rather than define a second one. The `ios-contracts`
  job is the first CI job to drive the rig app on a simulator, which phases 9–10 build on.
- **Out of scope:** the upload-job tier and the device extension host (a later phase); the App Group
  filesystem (phase 5); the backend (phase 7).
