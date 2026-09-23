## MODIFIED Requirements

### Requirement: Every test runs on every target its module declares

Logic tests SHALL live in `commonTest`, so they compile and run on every target the module declares
— JVM as the fast loop and `iosSimulatorArm64` as the target that ships. A platform-specific test
source set SHALL hold only what that platform's toolchain makes unrunnable elsewhere, and where a
sibling target has an equivalent implementation the two SHALL meet in a shared contract hosted in
`:test:contracts`' `commonMain` (capability `port-contracts`).

`commonTest` is where a test **goes** once placement is decided; it SHALL NOT be a reason to
**move** code. Hoisting a platform-to-neutral translation into `model/` to reach the faster test
loop is rejected by `module-architecture` ("Zones inside the core"), which is authoritative: the
translation belongs beside its inputs, where a test asserts against the platform's own symbols
rather than a copy of a constant.

Three kinds of non-`commonTest` source set exist today, and only the third is an exception to this
requirement:

- **Covers every target its module declares.** `:adapter:ios:ext-safe` and `:adapter:ios:app-only`
  declare no JVM target, so their `iosTest` sets *are* their common sets and run on
  `iosSimulatorArm64`. Not an exception.
- **Contract-paired split.** `:adapter:generic:app`'s `jvmTest` and `iosSimulatorArm64Test` hold the
  JVM-driver and native-driver halves of one shared storage contract. Together they cover both
  targets. Not an exception.
- **Genuine forgo.**
  - `:ui:components`' `jvmTest` holds Compose component tests with no iOS counterpart.
  - `:test:architecture` and `:tools:diagrams` are JVM-only `src/test`, because they read the
    repository's own text.
  - `:adapter:generic:app`'s `jvmTest` also holds the backend contracts' live bindings, because they
    launch the real backend as a local process, which a Kotlin/Native test executable under `simctl`
    cannot do.
  - `:test:integration` is JVM-only `src/test`, because its tests drive the control channel's JVM host,
    an in-process HTTP server over the world. That server is a JVM target.

  Each forgo SHALL name the coverage it forgoes where it is declared. For the backend bindings the forgo
  costs nothing: the clients under contract are `commonMain` code, identical on every target, and their
  Kotlin/Native compilation is covered by that module's `commonTest`. For `:test:integration` the forgo
  is the composed graph's Kotlin/Native run over fakes. It is covered in part by the core's own
  `iosSimulatorArm64` unit tests, and by the simulator app, which runs the composed graph natively over
  real adapters under the contracts and the journeys.

#### Scenario: Logic is placed in a platform test source set

- **WHEN** a logic test that would compile on every target the module declares is placed in a
  platform-specific test source set
- **THEN** it is moved to `commonTest`, so the iOS simulator run exercises it too

#### Scenario: Code is moved to reach the faster test loop

- **WHEN** a platform-to-neutral translation is relocated into a platform-free zone so that it can
  be exercised in `commonTest`
- **THEN** the relocation is rejected; the translation stays beside its inputs and is covered by the
  platform target's own tests

#### Scenario: A target is genuinely forgone

- **WHEN** a test can only run on one of its module's targets
- **THEN** it lives in that target's source set and the coverage it forgoes is stated where the
  source set is declared

#### Scenario: A binding must launch a local process

- **WHEN** a contract binding needs the real backend running as a local process
- **THEN** it lives in a `jvmTest` source set that states the simulator coverage it forgoes and why that
  coverage is not lost

#### Scenario: The integration suite forgoes Kotlin/Native

- **WHEN** the integration suite is run
- **THEN** it runs on the JVM only, and its build file states that the composed graph's Kotlin/Native run
  over fakes is forgone and where the native composition is still exercised

### Requirement: The seam-to-UI-state integration surface

`:test:integration` SHALL drive a **JVM host** of the control channel ("One control protocol, served by two
hosts") through the typed client, and nothing else. A test SHALL NOT name a world type, a port or a feature.
Each test SHALL start a fresh host in-process, over the mini-edge, and close it afterwards. The tests SHALL
run sequentially.

Tests SHALL stand on mocks only. There SHALL be no per-test selection of a real system: the port contracts
are what license the mocks, and a real system is exercised by the journeys ("All-real journeys are the
contracts' safety net").

**What a test asserts.** A test SHALL assert only **observable outcomes**:
- the projected `UiState`;
- what a system outside the app records:
  - the backend: objects, the union, manifests, device configuration, event existence and name, departed
    members, request counts;
  - the photo library;
  - the operating system's upload jobs;
  - the files in the download staging directory;
  - the diagnostics reporter's received dumps;
  - the pushes the backend sent;
  - the logs.

A test SHALL NOT assert **internal** state:
- ledger or download-store content, including the ledger counts the state projection carries;
- in-memory feature state;
- call counts on a mock whose real system records nothing a person could read.

Asserting `UiState` is required of the seams that reach it, not of every test. A selection-policy exclusion,
for example, is proved by the absence of bytes and of a manifest entry.

A test that needs a lever or read the protocol lacks SHALL add it as a vocabulary entry classified by both
hosts, not reach past the protocol. What the JVM host's world provides is owned by `harness-world-model`.

#### Scenario: A seam that reaches presentation

- **WHEN** an integration test drives a seam whose effect is projected into `UiState`
- **THEN** it asserts both an observable outcome outside the app and the projected `UiState`, both read
  through the client

#### Scenario: A seam that does not reach presentation

- **WHEN** an integration test drives a seam with no presentation projection
- **THEN** it asserts the observable outcomes alone, and that is the complete assertion

#### Scenario: A test's point is an internal fact

- **WHEN** the only thing a test could assert is a ledger row, a download-store row, in-memory feature
  state or a mock's call count
- **THEN** the test does not belong in this surface; its behaviour is covered by a unit test or a contract
  clause, and the change that removes it names that coverage

#### Scenario: A test needs a missing lever

- **WHEN** a migrated or new test needs to pull a lever the protocol does not carry
- **THEN** the lever joins the vocabulary with a classification on both hosts, and the test calls it through
  the client

### Requirement: The test-only modules and what each provides

The `:test:*` modules SHALL exist only where they provide something a production module may not, and
SHALL remain exempt from the production-module laws (`module-architecture`, "The module set
withholds"):

- **`:test:world`** — the controllable in-memory world. `:app:desktop` and the control channel's JVM host
  consume it, and it hosts the inbound ports' contract bindings (capability `harness-world-model`).
- **`:test:contracts`** — the port-contract mechanism and every port contract, consumed by the
  bindings' test source sets and linked into the app only under `-Psnapsync.rig=true` (capability
  `port-contracts`).
- **`:test:integration`** — the seam-to-UI-state surface above, driven over the protocol. It also holds the
  all-real journeys, as a separate task outside the canonical check.
- **`:test:architecture`** — JVM guards over the repository's own text (capability
  `architecture-guards`, which owns what each guard checks).
- **`:test:rig`** — the control channel: one HTTP protocol over a whole running application, served by
  two hosts ("One control protocol, served by two hosts"). Its iOS host is contained at compile time; its
  JVM host composes the world, and is tested in the canonical check through `:test:control`.
- **`:test:control`** — the typed JVM client of that protocol, and the home of the JVM host's tests.
- **`:test:edge`** — the real backend served as a local process for JVM tests: the backend contracts'
  live bindings and the world's real-backend option both stand on it.
- **`:test:harness-driver`** — non-gating dev infrastructure with no spec.

No production module's **main** source set SHALL depend on a `:test:*` module. A source set that a
build script adds only under a containment property (`module-architecture`, "A build-time-only module
is contained by compilation, not by a runtime check") is not a main source set of a production build,
and MAY depend on the contained module that property links.

#### Scenario: A production module reaches for test infrastructure

- **WHEN** a production module's main source set declares a dependency on a `:test:*` module
- **THEN** the dependency is rejected; a test source set extending a shared contract is a test
  compilation and introduces no production edge

#### Scenario: The integration suite reaches for the world

- **WHEN** a test in `:test:integration` names a type from `:test:world`, `ports/`, `flow/` or `compose/`
- **THEN** compilation fails, because the module's compile path carries only the client, the host's entry
  point and the read-model types

### Requirement: One control protocol, served by two hosts

The control channel (`:test:rig`) SHALL be one HTTP protocol, served by two hosts from the same server,
routes and state projection:
- the **app host**: the rig build of the iOS app, on a device or a simulator, over real ports;
- the **JVM host**: a JVM process whose application is the world's composed core and status host, over the
  world's doubles and the backend the world was built with. Both are composed by the same shared host
  composition the app host's shell calls (`module-architecture`, "One shared composition").

A host SHALL differ only in the hook it hands the server, never in a route or in the state encoding.
Both hosts SHALL bind the loopback address only.

**The verbs.** The protocol's verbs SHALL be:
- operating-system entry points (`/os`);
- user commands at the intent level (`/user`);
- device state and verbs (`/device`);
- `/health`.

It SHALL carry no click, semantics-tree or pixel verb: taps and pixels belong to the UI tier.

**`/user` is one table both hosts invoke.** It SHALL include every status-host intent a test or a mirror
needs to fire: leave, create, confirm and cancel a join, reconfigure, rename and its status
acknowledgement, confirm a switch, retry a failed details load, send diagnostics, and set the join or
reconfigure form's capture-date range without committing it.
- A command the composed core leaves absent on a build SHALL answer `409` naming why. The diagnostics send
  is absent on a build with no configured reporter.
- An intent excluded from the table SHALL carry a reason that is true on both hosts.

**`/device` and `/os` form one closed vocabulary.** Every host SHALL classify every entry as honoured or
refused, with a reason:
- `GET /device` SHALL answer that host's classification.
- A request for a refused entry SHALL answer `409` with the reason, never `404` and never a success that did
  nothing.
- A verb outside the vocabulary SHALL answer `404`.
- An entry a host leaves unclassified SHALL make `GET /device` fail naming it, without stopping the host.

A verb both hosts can honour SHALL have one request and response shape on both. A parameter one host cannot
honour SHALL answer `409` naming that parameter.

**The JVM host's levers and reads.** The JVM host SHALL expose, as `/device` verbs:
- the full-stack world inspector's levers (capability `full-stack-harness`);
- the levers and observable reads the integration surface needs ("The seam-to-UI-state integration
  surface"). These are:
  - backend reads through the world's backend-neutral inspection;
  - backend levers;
  - the world's clock, selection, relaunch, app-version, enumeration, import and log levers;
  - the staging-directory, reporter and album reads.

The app host refuses each world lever with the shared world-lever reason. A device fact the app host has but
does not yet wire SHALL be refused naming exactly that. The JVM host SHALL refuse the contract verb, naming
its host, because JVM contract bindings run in the canonical check.

**Testing the JVM host.** The JVM host SHALL be tested in the canonical check through the typed client, over
both of the world's backends. Those tests prove the protocol's fidelity to the application: routes, the
state encoding, the advertisement, the refusals and the client. The behavioural suite is the integration
surface, which drives the same host ("The seam-to-UI-state integration surface").

The iOS host's gallery seeder and wiper remain the channel's one untested code, for the reason its build
file records.

#### Scenario: The same state from either host

- **WHEN** a client reads `/device/state` from the JVM host and from the app host
- **THEN** both bodies decode to the same state type through the same compiler-generated encoder, carrying
  the real reduced UI state

#### Scenario: A host cannot honour a shared verb

- **WHEN** a client calls, on the app host, a world lever such as backend-offline
- **THEN** the host answers `409` with the reason, and `GET /device` on that host lists the lever as refused
  with the same reason

#### Scenario: The JVM host is asked to run a contract

- **WHEN** a client calls the contract verb on the JVM host
- **THEN** it answers `409` naming the `JVM` host and that JVM contracts run in the canonical check

#### Scenario: A vocabulary entry is added but not classified

- **WHEN** a verb joins the vocabulary and a host's hook does not classify it
- **THEN** that host's `GET /device` fails naming the verb, and the JVM host's tests fail in the canonical
  check

#### Scenario: A test asks for pixels

- **WHEN** a test needs a tap or a rendered pixel
- **THEN** it belongs in the UI tier's tests, because the protocol carries no such verb

#### Scenario: Diagnostics on a build with no reporter

- **WHEN** a client calls the diagnostics send on an app host built with no configured reporter
- **THEN** it answers `409` naming the missing reporter, and nothing leaves the device

## ADDED Requirements

### Requirement: All-real journeys are the contracts' safety net

A small set of **journeys** SHALL run end to end with every system real:
- the rig build of the iOS app, on simulators;
- the real backend, served locally;
- the real photo library.

They SHALL be written against the typed client only, as the integration surface is. They SHALL cover:
- creating an event and joining it;
- a member's own photos landing in the backend and the event union;
- a second member receiving those photos into their library.

A journey failure SHALL be read first as a **missing contract clause**: a behaviour the mocks do not hold,
fixed by a new clause, after which the mocked suite covers it.

The journeys SHALL gate merges, in the job that runs the in-app contracts (capability `ios-ci`). They SHALL
fail, never skip, when a host or the backend they are pointed at is absent.

#### Scenario: A journey finds a behaviour no mock holds

- **WHEN** a journey fails where the mocked suite passes
- **THEN** the fix adds the contract clause the mocks were missing, and the mocked suite then covers the
  behaviour

#### Scenario: A journey is run without its hosts

- **WHEN** the journey task runs with no simulator app or backend address given
- **THEN** it fails naming the missing address, rather than passing with nothing run
