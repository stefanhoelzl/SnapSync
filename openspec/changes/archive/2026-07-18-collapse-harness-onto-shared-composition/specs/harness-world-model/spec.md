# harness-world-model — delta for collapse-harness-onto-shared-composition

## MODIFIED Requirements

### Requirement: Controllable in-memory world module

The system SHALL provide a test-infra Kotlin Multiplatform module `:test:world` that runs the
**real** platform-agnostic stack against controllable in-memory infrastructure: the honest
in-memory port implementations SHALL live in `:adapter:fake` (package `app.snapsync.fake`; spec
`module-architecture`), and `:test:world` SHALL hold the **operator rigging** around them — the
backend store, the mini-edge, the levered fakes (`FakeBackgroundTransfer`,
`FakeDownloadTransport`, `FakePhotoLibraryImporter`, `FakeAlbumManager`,
`MutablePhotoAccessStatusSource`) and the wrappers that own the honest fakes' state cells
(`WorldGallery`, `RecordingDownloadStore`) — per the fake-honesty gate (`architecture-guards`).
The module SHALL declare targets `jvm()` and `iosSimulatorArm64` **only** (no `iosArm64` — it
never links into a shipped framework), so its logic and self-tests execute on **both** JVM and the
iOS simulator per testing rule 1. Its `commonMain` SHALL also host the shared storage-seam
contracts (`LedgerStoreContract`, `DownloadStoreContract`) — a test source set cannot be depended
on across modules, and this is the one test-infra `commonMain` every implementor's test source set
(`:test:world` commonTest for the fakes, `:adapter:generic` `jvmTest`/`iosSimulatorArm64Test` for
the SQLDelight stores) can reach. It SHALL be consumed by **both** the desktop full-stack harness
(`:app:desktop`) and the `:test:integration` module. Nothing in a production `domain`/`adapter`
module's **main** source sets SHALL depend on `:test:world` (the adapter test source sets extending
the contracts are test compilations, so no production edge is introduced).

#### Scenario: Runs on JVM and the simulator

- **WHEN** the module's self-tests are run
- **THEN** they execute on `jvm()` and `iosSimulatorArm64` (the module declares no `iosArm64` target)

#### Scenario: Consumed by both the harness and integration tests

- **WHEN** the desktop harness and `:test:integration` each assemble a world
- **THEN** both reach the same world class over the same `:adapter:fake` doubles, and no production
  main source set gains a dependency back into `:test:world`

#### Scenario: Rigging cannot live in a fake

- **WHEN** an operator lever (a settable cell, a failure switch, an inspection list) is needed on an
  honest `:adapter:fake` double
- **THEN** it is expressed in a `:test:world` wrapper owning the fake's constructor-injected state,
  never as a public member of the fake (the fake-honesty gate fails otherwise)

### Requirement: Real-stack composition helpers

The world SHALL assemble its upload cycle through the **same shared composition the device tiers
call** — `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition") over
the world's fakes — not through a world-local mirror of a composition root: the world supplies its
in-memory ports (`ConfigReader` over the config cell and the `membershipUnreadable` lever, the fake
`BackgroundTransfer`, the `:adapter:fake` ledger/discovery/manifest/marker stores, the mini-edge HTTP
seams) and `uploadCore` builds the real `SyncEngine` + `EdgeUploadRequestProvider` + `UploadCycle` +
`ExtensionReconciler` + `DeviceManifestProducer` graph, exactly as it does for the device roots. The
app-side graph — download, status, membership, creation, the command bundle — SHALL come from the
composed `AppCore` (see "The world composes the app graph through snapSyncApp"). Only the platform
edges (`BackgroundTransfer`, `DownloadTransport`, `PhotoLibraryImporter`), the storage seams, and the
HTTP client SHALL be fakes; everything above them SHALL be the shipped production code.

#### Scenario: The composed upload path exercises the real cycle

- **WHEN** the world's `uploadCore`-assembled cycle is invoked
- **THEN** the real `SyncEngine`, `EdgeUploadRequestProvider`, and `UploadCycle` run, and only the job
  platform, discovery store, ledger backend, and HTTP client are fakes

#### Scenario: A wiring difference from production is impossible

- **WHEN** the world and a device tier each assemble an upload cycle
- **THEN** both call the same `uploadCore` function over different port implementations, so the world
  cannot carry gate, reconcile, manifest, or policy wiring production lacks (or vice versa)

#### Scenario: Production seams are not duplicated

- **WHEN** the world composes the manifest path
- **THEN** its `Enrollment` port is `:adapter:generic`'s `HttpEnrollment` over the injected mini-edge
  client — the world carries no copy of any production adapter (the step-10 death of the world's
  byte-identical `HttpEnrollment` closed the deletion ledger's last row)

## ADDED Requirements

### Requirement: The world composes the app graph through snapSyncApp

The world SHALL hold the app-side graph as a real `AppCore` produced by the **same** `snapSyncApp`
composition the iOS app shell calls (spec `module-architecture`, "One shared composition"),
constructed over an `AppPorts` whose ports are the world's fakes and mini-edge seams and whose
shell-supplied lambdas are the world's operator surface: `provision` writes the config cell,
`notifyLeave` is the real backend DELETE seam, `onEventMinted` is a settable routing hook (default:
provision the minted event directly; the desktop inspector points it at the status host's
pending-join gate), and `uploadProducer` is inert (nothing auto-runs — the operator plays the OS).
The world's exposed download controller, status sources, creation status, join use-case, and
user-tap command bundle SHALL be `AppCore`'s instances — never world-local rebuilds — so a wiring
difference between the harness and the app shell is impossible rather than undetected.

Two named deviations are permitted, each an operator-synchronicity concern and nothing else: the
world MAY re-install the composed `downloadJobs.onStaged` hook with an **identical body plus Job
retention** (so `stageAllDownloads` is complete on return), and the world's operator `leave()` MAY
remain a synchronous faithful edge beside the bundle's production-ordered leave (whose backend
notify is fire-and-forget by design); tests driving the bundle's leave await the backend outcome.

#### Scenario: The harness's app graph is the production graph

- **WHEN** the world harness or an integration test fires a user-tap command (create, commit-join,
  leave)
- **THEN** the command runs through `AppCore.userCommands` — the same compose-built bundle the iOS
  shell injects — over the world's ports, and its effects land in the world's fakes and mini-edge

#### Scenario: The world cannot rebuild what the composition owns

- **WHEN** the world or its inspector needs a status source, download controller, or join use-case
- **THEN** it reads the composed `AppCore`'s instance; no second assembly of a feature graph exists
  in harness code
