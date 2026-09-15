## MODIFIED Requirements

### Requirement: Controllable in-memory world module

The system SHALL provide a test-infra Kotlin Multiplatform module `:test:world` that runs the
**real** platform-agnostic stack against controllable in-memory infrastructure: the honest
in-memory port implementations SHALL live in `:adapter:generic:fake` (package `app.snapsync.fake`; spec
`module-architecture`), and `:test:world` SHALL hold the **operator rigging** around them — the
backend store, the mini-edge, the levered fakes (`FakeBackgroundTransfer`, `FakeUploadDiscovery`,
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

### Requirement: Operator-driven, inspectable upload-job lifecycle

The world SHALL provide a fake `BackgroundTransfer` that models the OS upload-job lifecycle as an
operator-driven, **inspectable** queue implementing every seam method. Like the device transports, it SHALL
receive the ledger only as a `TransferRecord` (`sync-ledger`) and SHALL record each terminal outcome through
its guarded `markTerminal`; it SHALL serve no library read. Like the OS-driven queue it models, it SHALL
report the absence of a live set from `liveKeys()`, so the world runs no stranded reconciliation.
`createJob` SHALL enqueue a PENDING job and return `CREATED`, unless a **settable job-limit** is reached
(returning `LIMIT_EXCEEDED`) or a forced create-failure is set (returning `FAILED`). An operator **complete**
action SHALL deposit the job's object key into the backend object store **store-direct** (byte transfer
is not routed through ktor) and move the job to the acknowledge bucket, so the next cycle records it
`COMPLETED`. An operator **fail** action SHALL move the job to the retry bucket carrying a chosen engine
`UploadError` (`Network`, `Http`, `Cancelled`, or `Unknown`), driving the real engine retry chain with
an incremented attempt. The queue's pending/retry/acknowledge buckets and per-job attempt SHALL be
inspectable so tests assert the lifecycle, not only the final outcome.

#### Scenario: Complete deposits the object and the ledger records COMPLETED

- **WHEN** the operator completes a created job and the next upload cycle runs
- **THEN** the object key is present in the backend store and the ledger holds a `COMPLETED` row for it

#### Scenario: Fail drives the real retry chain

- **WHEN** the operator fails a created job with a chosen `UploadError` and the next cycle runs
- **THEN** the engine answers `Retry`, the job is re-created, and its attempt count increments

#### Scenario: Job-limit truncates creation but not the cycle

- **WHEN** the job-limit is set below the number of `Work` resources in a cycle
- **THEN** `createJob` returns `LIMIT_EXCEEDED`, the cycle returns `PROCESSING`, the un-enqueued
  resources hold `DISCOVERED` rows, the discovery cursor **has** advanced, and the cycle still
  published its device manifest

#### Scenario: The fake queue holds no ledger store

- **WHEN** the world constructs its fake job queue
- **THEN** it is given the world's ledger as a `TransferRecord`, exactly as a device transport is

### Requirement: Token-delta discovery feed driven by the in-memory gallery

The world SHALL provide a fake `UploadDiscovery` whose `discover(sinceToken, policy)` derives its change feed
from the in-memory gallery — the honest `InMemoryCandidateSource`, which answers the single
`CandidateSource.candidates(policy)` read over the world-owned asset cell and whose candidates map their
resources through the real shared fan-out when the cycle asks for them. Adding an asset SHALL surface it
as a new `Candidate` in `Discovery.candidates`; removing an asset SHALL surface its id in
`Discovery.removedAssetIds`; and an operator **expire-token** action SHALL return
`Discovery.fullEnumeration = true` carrying the whole current key-set (the routine token-expiry path).

The same fake SHALL resolve ledger keys to uploadable resources (capability `ios-url-session-upload`) from the
world's in-memory gallery, and both reads SHALL be observable, so a test can assert **which keys** a cycle
resolved and **whether** it consumed the feed — the evidence that it enqueued from the ledger rather than from
the walk's output. A key whose asset the operator has removed from the gallery SHALL resolve to nothing.

**A removal SHALL mean the asset left the LIBRARY, never that the policy stopped admitting it.**
`Discovery.removedAssetIds` SHALL therefore be derived by diffing the **unscoped** gallery — the same raw
asset cell the fake resolves keys from — and SHALL NOT be derived by diffing the policy-scoped
`candidates(policy)` read, however convenient that read is to have in hand. This mirrors the device, where
removals are PhotoKit's `deletedLocalIdentifiers`: assets that were deleted, not assets that fell out of a
fetch predicate. Diffing the scoped read instead makes a **narrowing reconfigure** arrive at the cycle as
a mass deletion, and the cycle then marks exactly the rows a narrowing excludes absent — performing, in
the harness alone, the job the enqueue admission does on a device (capability `photo-selection-policy`),
and thereby hiding whether that admission exists at all. That is not hypothetical: it is how the world
answered while the uploader was still sending the photos a narrowing had excluded, and the first
integration test written against the real defect passed its upload assertions and failed on retention.

A full enumeration SHALL reconcile **nothing** away. The cycle no longer prunes or marks rows for assets
an enumeration did not return (capability `sync-ledger`), so the expire-token path exercises
re-enumeration and cursor advance, not retention. Removal reaches the ledger by exactly one route — the
`removedAssetIds` signal — which marks those rows absent rather than deleting them, so the world can
drive both the "reported removal" case and the "removal the feed never reported" case, and they have
different observable outcomes.

#### Scenario: Adding an asset yields a new resource

- **WHEN** an asset is added to the in-memory gallery and discovery runs
- **THEN** `Discovery.candidates` carries that asset, and its resources fan out when the cycle asks

#### Scenario: Removing an asset yields a removed id

- **WHEN** an asset is removed and discovery runs
- **THEN** `Discovery.removedAssetIds` carries its id and the cycle marks its ledger rows absent, so
  they stop counting and stop being listed while remaining readable

#### Scenario: Narrowing the policy is not a removal

- **WHEN** the membership's capture-date cutoff is raised so assets still present in the gallery fall
  outside it, and discovery then runs
- **THEN** `Discovery.removedAssetIds` is empty and those assets' ledger rows are left unmarked — the
  world shows the narrowing exactly as a device does, as rows the enqueue admission declines to upload
  rather than as rows the feed retracted

#### Scenario: Expiring the token forces a full enumeration

- **WHEN** the operator expires the token and discovery runs
- **THEN** `Discovery.fullEnumeration` is `true` with the whole key-set, and the cycle re-enumerates and
  advances its cursor

#### Scenario: A removal the feed never reported leaves the rows alone

- **WHEN** an asset is removed from the in-memory gallery while the token is expired, so no
  `removedAssetIds` signal is ever produced for it, and a full enumeration then runs
- **THEN** its ledger rows survive unmarked — the world can therefore demonstrate that an unreported
  deletion leaves the asset listed rather than silently retracted

#### Scenario: A later cycle enqueues the remainder from the ledger

- **WHEN** a job-limited cycle is followed by another cycle with no gallery change in between
- **THEN** the remainder is enqueued by resolving its `DISCOVERED` rows' keys, not from anything the
  change feed returned — which reports nothing, since nothing changed

#### Scenario: A removed asset resolves to nothing

- **WHEN** the operator removes an asset from the gallery and a cycle resolves a ledger key for it
- **THEN** the resolution returns nothing for that key

### Requirement: Real-stack composition helpers

The world SHALL assemble its upload cycle through the **same shared composition the device tiers
call** — `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition") over
the world's fakes — not through a world-local mirror of a composition root: the world supplies its
in-memory ports (`ConfigReader` over the config cell and the `membershipUnreadable` lever, the fake
`BackgroundTransfer`, the fake `UploadDiscovery`, the `:adapter:generic:fake`
ledger/discovery/manifest/marker stores, the mini-edge HTTP seams) and `uploadCore` builds the real
`SyncEngine` + `EdgeUploadRequestProvider` + `UploadCycle` + `UploadReconciler` + `DeviceManifestProducer`
graph, exactly as it does for the device roots. The app-side graph — download, status, membership, creation,
the command bundle — SHALL come from the composed `AppCore` (see "The world composes the app graph through
snapSyncApp"). Only the platform edges (`BackgroundTransfer`, `UploadDiscovery`, `DownloadTransport`,
`PhotoLibraryImporter`), the storage seams, and the HTTP client SHALL be fakes; everything above them SHALL
be the shipped production code.

#### Scenario: The composed upload path exercises the real cycle

- **WHEN** the world's `uploadCore`-assembled cycle is invoked
- **THEN** the real `SyncEngine`, `EdgeUploadRequestProvider`, and `UploadCycle` run, and only the job
  platform, library discovery, discovery store, ledger backend, and HTTP client are fakes

#### Scenario: A wiring difference from production is impossible

- **WHEN** the world and a device tier each assemble an upload cycle
- **THEN** both call the same `uploadCore` function over different port implementations, so the world
  cannot carry gate, reconcile, manifest, or policy wiring production lacks (or vice versa)

#### Scenario: Production seams are not duplicated

- **WHEN** the world composes the manifest path
- **THEN** its `Enrollment` port is `:adapter:generic:app`'s `HttpEnrollment` over the injected mini-edge
  client — the world carries no copy of any production adapter (the step-10 death of the world's
  byte-identical `HttpEnrollment` closed the deletion ledger's last row)
