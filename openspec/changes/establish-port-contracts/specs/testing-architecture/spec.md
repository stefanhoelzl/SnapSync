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
- **Genuine forgo.** `:ui:components`'s `jvmTest` holds Compose component tests with no iOS
  counterpart; `:test:architecture` and `:tools:diagrams` are JVM-only `src/test` because they read
  the repository's own text. Each SHALL name the coverage it forgoes where it is declared.

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

### Requirement: Fake-driven feature tests live in the fake module

Feature tests that drive `:domain` subjects through the honest in-memory port implementations SHALL
live in `:adapter:generic:fake`'s own `commonTest`. `:domain`'s test source set cannot reach those
fakes: `:adapter:generic:fake` depends on `:domain`, so a test edge back from `:domain` is a project
dependency cycle, and a test source set cannot be depended on across modules at all — which is the
same constraint that puts the shared port contracts in `:test:contracts`' `commonMain`
(`port-contracts`). The fakes' own contract bindings live in the same `commonTest`, for the same
reason: only the fake module's test source set can construct an `internal` fake in a chosen state.

`:domain`'s `commonTest` SHALL therefore hold only tests standing on pure functions or hand-written
local doubles.

Two consequences SHALL be stated rather than discovered: a feature's tests may be split across two
modules, so a reader looking for them must look in both; and `:adapter:generic:fake`'s `commonTest` is a
**test host** that legitimately sees more than any other consumer. The fakes are `internal`, exported
through factories returning the port type, so no other module can name an implementation or reach a
member the port does not declare — but `internal` is module-scoped and a module's own test source set is
inside it. That is what makes the fake module the only place these tests can live, and it is a property
of the module boundary rather than of a gate that reads source.

#### Scenario: A feature test needs a fake

- **WHEN** a `:domain` feature test requires an honest in-memory port implementation
- **THEN** it is written in `:adapter:generic:fake`'s `commonTest`, not in `:domain`'s

#### Scenario: A test-only helper is added to the fake module

- **WHEN** a helper is added under `:adapter:generic:fake`'s `commonTest`
- **THEN** the fake-honesty gate does not scan it, because the gate's subject is what the fakes
  expose in their main source sets

### Requirement: The test-only modules and what each provides

The `:test:*` modules SHALL exist only where they provide something a production module may not, and
SHALL remain exempt from the production-module laws (`module-architecture`, "The module set
withholds"):

- **`:test:world`** — the controllable in-memory world, consumed by both `:app:desktop` and
  `:test:integration` (capability `harness-world-model`).
- **`:test:contracts`** — the port-contract mechanism and every port contract, consumed by the
  bindings' test source sets and linked into the app only under `-Psnapsync.rig=true` (capability
  `port-contracts`).
- **`:test:integration`** — the seam-to-UI-state surface above.
- **`:test:architecture`** — JVM guards over the repository's own text (capability
  `architecture-guards`, which owns what each guard checks).
- **`:test:harness-driver`** and **`:test:rig`** — non-gating dev infrastructure with no spec,
  contained at compile time.

No production module's **main** source set SHALL depend on a `:test:*` module. A source set that a
build script adds only under a containment property (`module-architecture`, "A build-time-only module
is contained by compilation, not by a runtime check") is not a main source set of a production build,
and MAY depend on the contained module that property links.

#### Scenario: A production module reaches for test infrastructure

- **WHEN** a production module's main source set declares a dependency on a `:test:*` module
- **THEN** the dependency is rejected; a test source set extending a shared contract is a test
  compilation and introduces no production edge
