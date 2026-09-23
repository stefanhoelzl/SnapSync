## MODIFIED Requirements

### Requirement: Controllable in-memory world module

The system SHALL provide a test-infra Kotlin Multiplatform module `:test:world` that runs the
**real** platform-agnostic stack against controllable in-memory infrastructure: the honest
in-memory port implementations SHALL live in `:adapter:generic:fake` (package `app.snapsync.fake`; spec
`module-architecture`), and `:test:world` SHALL hold the **operator rigging** around them — the
backend store, the mini-edge, the levered fakes of ports no contract binds yet (`FakeBackgroundTransfer`,
`FakeDownloadTransport`) and the wrappers that own the honest fakes' state cells and carry the operator's
levers over them (`WorldGallery`, `RecordingDownloadStore`, and the wrappers over the honest upload-discovery,
album-manager, photo-importer and photo-access fakes) — per the fake-honesty gate (`architecture-guards`).
A port that a contract binds (capability `port-contracts`) SHALL be doubled in the world by its honest,
contract-bound fake, wrapped; the world SHALL NOT keep a second, levered implementation of that port, whose
behaviour no contract would hold to the real adapter's.
The module SHALL declare targets `jvm()` and `iosSimulatorArm64` **only** (no `iosArm64` — it
never links into a shipped framework), so its logic and self-tests execute on **both** JVM and the
iOS simulator per capability `testing-architecture` ("Every test runs on every target its module
declares"). It SHALL NOT host port contracts: those live in `:test:contracts` (capability
`port-contracts`), which is also the only test-infra module the device app may link. It SHALL be consumed by **both** the desktop full-stack harness
(`:app:desktop`) and the `:test:integration` module. Nothing in a production `domain`/`adapter`
module's **main** source sets SHALL depend on `:test:world`.

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

#### Scenario: A contracted port's double in the world

- **WHEN** the world needs a failure lever on a port whose honest fake a contract binds, such as the album
  manager
- **THEN** the lever lives on a world wrapper around that fake, and the fake answering beneath it is the one
  the contract holds to the real adapter
