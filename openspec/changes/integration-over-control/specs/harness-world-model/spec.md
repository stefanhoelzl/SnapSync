## MODIFIED Requirements

### Requirement: Controllable in-memory world module

**What the module is.** The system SHALL provide a test-infra Kotlin Multiplatform module `:test:world` that
runs the **real** platform-agnostic stack against controllable in-memory infrastructure:
- the honest in-memory port implementations SHALL live in `:adapter:generic:fake` (package `app.snapsync.fake`;
  spec `module-architecture`);
- `:test:world` SHALL hold the **operator rigging** around them, per the fake-honesty gate
  (`architecture-guards`):
  - the backend store and the mini-edge;
  - the levered fakes of ports no contract binds yet (`FakeBackgroundTransfer`, `FakeDownloadTransport`);
  - the wrappers that own the honest fakes' state cells and carry the operator's levers over them
    (`WorldGallery`, `RecordingDownloadStore`, and the wrappers over the honest upload-discovery,
    album-manager, photo-importer and photo-access fakes).

**Contracted ports.** A port that a contract binds (capability `port-contracts`) SHALL be doubled in the world
by its honest, contract-bound fake, wrapped. The world SHALL NOT keep a second, levered implementation of that
port, whose behaviour no contract would hold to the real adapter's.

**Targets.** The module SHALL declare targets `jvm()` and `iosSimulatorArm64` **only** (no `iosArm64` — it
never links into a shipped framework), so its logic and self-tests execute on **both** JVM and the iOS
simulator per capability `testing-architecture` ("Every test runs on every target its module declares").

**Contracts.** It SHALL NOT host port contracts: those live in `:test:contracts` (capability
`port-contracts`), which is also the only test-infra module the device app may link. Its test source sets
SHALL host the inbound ports' contract bindings over the composed world ("The world hosts the inbound ports'
contract bindings").

**Consumers.** It SHALL be consumed by the desktop full-stack harness (`:app:desktop`) and by the control
channel's JVM host (`:test:rig`). The integration surface (`:test:integration`) reaches it **only** through
that host's protocol and SHALL NOT depend on it. Nothing in a production `domain`/`adapter` module's **main**
source sets SHALL depend on `:test:world`.

#### Scenario: Runs on JVM and the simulator

- **WHEN** the module's self-tests are run
- **THEN** they execute on `jvm()` and `iosSimulatorArm64` (the module declares no `iosArm64` target)

#### Scenario: Consumed by the harness and the JVM host

- **WHEN** the desktop harness and the control channel's JVM host each assemble a world
- **THEN** both reach the same world class over the same `:adapter:generic:fake` doubles, the integration
  suite reaches it only through the protocol, and no production main source set gains a dependency back into
  `:test:world`

#### Scenario: Rigging cannot live in a fake

- **WHEN** an operator lever (a settable cell, a failure switch, an inspection list) is needed on an
  honest `:adapter:generic:fake` double
- **THEN** it is expressed in a `:test:world` wrapper owning the fake's constructor-injected state,
  never as a public member of the fake (the fake-honesty gate fails otherwise)

#### Scenario: A contracted port's double in the world

- **WHEN** the world needs a failure lever on a port whose honest fake a contract binds, such as the album
  manager
- **THEN** the lever lives on a world wrapper around that fake, and the fake answering beneath it is the one
  the contract holds to the real adapter

### Requirement: Integration tests assert UiState and world outcomes

**What the surface reads.** The integration surface SHALL read the world through the control channel's JVM
host only. That host's `/device` verbs SHALL expose, as observable reads, what the world's systems outside the
app record:
- the backend's objects, union, manifests, device configuration, event existence and name, departed members,
  and request counts, all through the backend-neutral inspection;
- the photo library's census, albums and original filenames;
- the operating system's upload jobs;
- the staging directory's files;
- the diagnostics reporter's received dumps;
- the pushes the backend sent;
- the logs.

It SHALL NOT expose ledger or download-store content as a test assertion surface. The state projection's
ledger counts are for reading by hand, not for tests.

**What a test asserts.** A test SHALL assert those outcomes from world mutations and cycle invocations —
never from injected `SyncEvent`s alone. Where the seam under test reaches presentation, it SHALL **also**
assert the projected `UiState`. Where it does not, the observable outcomes are the complete assertion: an
exclusion is proved by the absence of bytes and of a manifest entry, neither of which is a `UiState`.

This is the seam-to-UI-state integration surface owned by capability `testing-architecture`, spanning the real
upload/download execution edge. It SHALL run on the JVM.

#### Scenario: A completed upload advances both UiState and the backend

- **WHEN** an asset is seeded, its job created and completed, and the cycle plus a status refresh run
- **THEN** the projected `UiState` reaches `Joined(SyncHealth.InSync)` **and** the object is present in
  the backend's per-device listing, both read through the protocol

#### Scenario: A foreign download imports and is observable

- **WHEN** a foreign device's complete asset is reconciled, staged, and imported
- **THEN** the imported asset is present in the photo library read through the protocol and is not uploaded
  back (no job, no object), alongside `UiState`

### Requirement: The world composes the app graph through snapSyncApp

**The core and the host.** The world SHALL hold the app-side graph as a real `AppCore` **and** a real status
host, produced by the **same** shared host composition the iOS app shell calls (spec
`module-architecture`, "One shared composition"). That composition calls `snapSyncApp`, constructed over an
`AppPorts` whose ports are the world's fakes and mini-edge seams. The world SHALL NOT assemble a status host,
install a subscription, or wire the HTTP client's credential and version callbacks itself.

**No second body for core machinery.** The world SHALL bind no `AppPorts` field to a body of its own that
stands in for core machinery. The provision a join performs, the attestation refresh and the push
registration are built by `snapSyncApp` and run for real in the world, exactly as on iOS.

**The operator surface** is the world's own levers **beside** the composed core (the operator `provision()`,
`leave()`, `relaunch()`, the inject/fail levers), never a second body for a seam the core calls:
- `onEventMinted` is a routing hook. Its default provisions the minted event through the composed Provision
  flow; the desktop inspector and the JVM host point it at the status host's pending-join gate.
- The upload producer is inert: nothing auto-runs, and the operator plays the OS.

The world's exposed download controller, status sources, status host, creation status, join use-case, and
user-tap command bundle SHALL be the composition's instances — never world-local rebuilds — so a wiring
difference between the harness and the app shell is impossible rather than undetected.

**The one permitted deviation** is an operator-synchronicity concern and nothing else: the world's operator
`leave()` MAY remain a synchronous faithful edge beside the bundle's production-ordered leave (whose backend
notify is fire-and-forget by design). Tests driving the bundle's leave await the backend outcome.

The former second deviation — re-installing the composed `downloadJobs.onStaged` hook with an identical
body plus Job retention — is **withdrawn**. It existed only because the seam was non-suspend and the
composition discarded the Job; the feature now tracks its own launches, so the harness has nothing left to
re-install and the permission would only license a divergence nobody needs.

#### Scenario: The harness's app graph is the production graph

- **WHEN** the world harness or the JVM host fires a user-tap command (create, commit-join, leave)
- **THEN** the command runs through `AppCore.userCommands` — the same compose-built bundle the iOS
  shell injects — over the world's ports, and its effects land in the world's fakes and mini-edge

#### Scenario: A join in the world runs the real Provision flow

- **WHEN** a test commits a join through the command bundle
- **THEN** the join's provision step runs the composed `flow/Provision` — membership entry, upload
  transition, push registration, album ensure, status refresh and download reconcile — with no world-local
  body in its place

#### Scenario: The world cannot rebuild what the composition owns

- **WHEN** the world or its inspector needs a status source, the status host, a download controller, or a
  join use-case
- **THEN** it reads the composition's instance; no second assembly of a feature graph or a status host
  exists in harness code

#### Scenario: A source the iOS host observes is missing from the world's host

- **WHEN** the iOS shell's status host observes a read-model (for example the version refusal)
- **THEN** the world's host observes it too, because both are built by the one shared host composition

## ADDED Requirements

### Requirement: The world relaunches its app over its durable state

The world SHALL offer a `relaunch()` lever that models process death and a cold launch:
1. It ends the current composition's scope.
2. It runs the shared host composition again over the same **durable** state, and performs the cold-start
   sequence the iOS shell performs.

The durable state is:
- the ledger store and the download store;
- the config and secure stores;
- the staged files;
- the gallery and the album map;
- the backend;
- the operating-system-held transfer sessions of both transfer doubles.

Every other cell SHALL start fresh. The world SHALL classify each cell it holds as durable or process
memory in one place, and a world test SHALL pin that classification.

#### Scenario: A completion learned by a dead process

- **WHEN** an upload job completes, the world relaunches, and a cycle runs
- **THEN** the relaunched app neither re-uploads the asset nor loses it, because the ledger and the
  transfer sessions were durable

#### Scenario: A relaunch performs no status read of its own

- **WHEN** the world relaunches while joined with photos
- **THEN** the status host starts from the un-read state, as a cold-launched app does, until a trigger reads
  status

### Requirement: The mini-edge records the pushes it would send

The mini-edge SHALL record each push the backend would send, against the registered token and device. The
world SHALL expose the record as a backend-neutral read, which answers unavailable on the real backend.

The world SHALL deliver no push on its own: the operator plays the operating system and fires the silent-push
entry point.

#### Scenario: A registered device is notified

- **WHEN** a device registers a push token and another member's upload changes the event
- **THEN** the pushes read lists one for that device's token, and no silent-push entry point has fired

### Requirement: The world hosts the inbound ports' contract bindings

The inbound ports' contracts (`PlatformEntriesContract`, `ExtensionEntriesContract`) SHALL be bound in
`:test:world`'s test source sets over the world's composed core and host, on both hosts they name (`JVM` and
`IOS_SIM_KEXE`). A binding SHALL live in a module that runs its host's target, so no binding is counted by
coverage while its host no longer runs it.

#### Scenario: The integration module leaves the simulator

- **WHEN** the integration module declares no simulator target
- **THEN** the inbound ports' simulator binding still runs, from `:test:world`'s simulator test source set
