## Purpose

A controllable in-memory "world" (`:test:world`) that the REAL platform-agnostic stack — `SyncEngine`
+ `UploadCycle`, `ExtensionReconciler`, `DeviceManifestProducer`, `DownloadController` +
`QueuedPhotoDownloadJobs`, `OwnDeviceGalleryStatusSource` + `LedgerBackedSyncStatusSource`,
`CreateEvent` — runs against, so the whole system (upload AND download) is observable and testable on
JVM + `iosSimulatorArm64` without a device. It provides a backend object store computing the edge's read-models faithfully (drift
accepted, no golden fixture), a Ktor `MockEngine` mini-edge over the four common-Ktor seams,
operator-driven upload/download job fakes, a one-own-plus-injectable-foreign device model, controllable
failure levers, and composition helpers mirroring the extension composition root. Consumed by BOTH the
desktop full-stack harness (`:app:desktop`) and `:test:integration`.

It exists because the code that most needs coverage — the upload cycle's adjudication, the rejoin reconcile,
the download echo-suppression — is exactly the code that ran only inside an iOS extension that cannot be
tested on a simulator. Faking the *execution edge* rather than the logic lets the real stack run anywhere,
which is what makes the standing target rule (capability `testing-architecture`, "Every test runs on
every target its module declares") achievable for orchestration and not just for pure functions.

Decision record: `changes/archive/2026-07-03-add-harness-world-model`.
The world composes attestation because `AppPorts` requires the seams, and leaves it **inert by default** —
`isSupported()` is false, so a refresh returns early without attesting, exactly as it does in the upload
extension and on a simulator. An opt-in lever turns it on for the tests that need a credential *change* to
happen at all. Two backend behaviours it still does not model, stated so they are not assumed: the token
gate itself, and the `401` a device-scoped write answers when the backend holds no attestation record.


## MODIFIED Requirements

### Requirement: Controllable in-memory world module

The system SHALL provide a test-infra Kotlin Multiplatform module `:test:world` that runs the
**real** platform-agnostic stack against controllable in-memory infrastructure: the honest
in-memory port implementations SHALL live in `:adapter:generic:fake` (package `app.snapsync.fake`; spec
`module-architecture`), and `:test:world` SHALL hold the **operator rigging** around them — the
backend store, the mini-edge, the levered fakes (`FakeBackgroundTransfer`,
`FakeDownloadTransport`, `FakePhotoLibraryImporter`, `FakeAlbumManager`,
`MutablePhotoAccessStatusSource`) and the wrappers that own the honest fakes' state cells
(`WorldGallery`, `RecordingDownloadStore`) — per the fake-honesty gate (`architecture-guards`).
The module SHALL declare targets `jvm()` and `iosSimulatorArm64` **only** (no `iosArm64` — it
never links into a shipped framework), so its logic and self-tests execute on **both** JVM and the
iOS simulator per capability `testing-architecture` ("Every test runs on every target its module
declares"). Its `commonMain` SHALL also host the shared storage-seam
contracts (`LedgerStoreContract`, `DownloadStoreContract`) — a test source set cannot be depended
on across modules, and this is the one test-infra `commonMain` every implementor's test source set
(`:test:world` commonTest for the fakes, `:adapter:generic:app` `jvmTest`/`iosSimulatorArm64Test` for
the SQLDelight stores) can reach. It SHALL be consumed by **both** the desktop full-stack harness
(`:app:desktop`) and the `:test:integration` module. Nothing in a production `domain`/`adapter`
module's **main** source sets SHALL depend on `:test:world` (the adapter test source sets extending
the contracts are test compilations, so no production edge is introduced).

#### Scenario: Runs on JVM and the simulator

- **WHEN** the module's self-tests are run
- **THEN** they execute on `jvm()` and `iosSimulatorArm64` (the module declares no `iosArm64` target)

#### Scenario: Consumed by both the harness and integration tests

- **WHEN** the desktop harness and `:test:integration` each assemble a world
- **THEN** both reach the same world class over the same `:adapter:generic:fake` doubles, and no production
  main source set gains a dependency back into `:test:world`

#### Scenario: Rigging cannot live in a fake

- **WHEN** an operator lever (a settable cell, a failure switch, an inspection list) is needed on an
  honest `:adapter:generic:fake` double
- **THEN** it is expressed in a `:test:world` wrapper owning the fake's constructor-injected state,
  never as a public member of the fake (the fake-honesty gate fails otherwise)


### Requirement: Integration tests assert UiState and world outcomes

The `:test:integration` module SHALL consume `:test:world` and `:ui:presentation` (re-homed from
`:domain:presentation` at migration step 9) to assert **world outcomes** from world mutations and cycle
invocations — never injected `SyncEvent`s alone. World outcomes SHALL include: objects landed in the
backend store (the per-device listing grows), ledger rows reaching `COMPLETED`, and foreign photos
imported into the in-memory gallery. Where the seam under test reaches presentation, the test SHALL
**also** assert the projected `UiState`; where it does not, the world outcomes are the complete
assertion — an exclusion is proved by the absence of bytes, of a ledger row, and of a manifest entry,
none of which is a `UiState`. This is the seam-to-UI-state integration surface owned by capability
`testing-architecture`, spanning the real upload/download execution edge, and it SHALL run on JVM and
`iosSimulatorArm64`.

#### Scenario: A completed upload advances both UiState and the store

- **WHEN** an asset is added, its job created and completed, and the cycle plus a status refresh run
- **THEN** the projected `UiState` reaches `Joined(SyncHealth.InSync)` **and** the object is present in
  the per-device listing with a `COMPLETED` ledger row

#### Scenario: A foreign download imports and is observable

- **WHEN** a foreign device's complete asset is reconciled, staged, and imported
- **THEN** the imported asset is present in the in-memory gallery and (via suppression) is not
  re-uploaded, and the outcome is assertable at the store/gallery level alongside `UiState`
