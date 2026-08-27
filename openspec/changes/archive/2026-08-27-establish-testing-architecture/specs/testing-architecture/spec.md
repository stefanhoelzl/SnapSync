## Purpose

The testing contract of record: what is tested, where each kind of test lives, which targets it runs
on, why the test-only modules exist, and what is deliberately not tested at all.

Three standing rules have governed this codebase since before the module migration, and are cited as
binding law in **24 places across 10 specs** — "by law", "by rule", "by project rule", and four times
by bare ordinal ("per testing rule 1") — while the rules themselves lived only in CLAUDE.md prose,
which no gate holds to anything and which a spec may not defer its meaning to. The consequence is not
hypothetical in the direction it points: the untested app shell became the most-churned,
most-defective region of the codebase (`module-architecture`), and the rule that keeps it untested
was itself unwritten. Written down, the rules were found false in six places against the tree they
described.

This spec states the rules, names the exceptions each one actually has today, and names the residual
risk each one leaves uncovered — so that changing one is a spec delta rather than an edit to a prose
section.

It states **where tests go and what they may reach**. It does not state what any individual
capability must test — each capability's spec owns its own scenarios — and it does not own the
guards that mechanize placement (`architecture-guards`), the workflows that run the suites
(`ci-build`, `ios-ci`), or the harnesses tests stand on (`harness-world-model`,
`full-stack-harness`, `desktop-test-harness`). These are cited, never restated.

Decision record: `changes/archive/2026-08-27-establish-testing-architecture`.

## ADDED Requirements

### Requirement: A test lives with the code it tests

A test SHALL live in the module that owns the logic under test. A test that would need a technology
its owning module withholds SHALL be read as evidence that the logic sits in the wrong module, and
SHALL NOT be grounds for adding a dependency to that module.

This is the general form of the shell rule and the fake re-homing rule below. It is also the failure
this project already paid for: behaviour placed in a composition root could not be reached by any
test, so no contract described it and none was written.

#### Scenario: A test needs a dependency its module withholds

- **WHEN** covering a behaviour would require adding a third-party or platform dependency to the
  module that declares it
- **THEN** the behaviour is relocated to a module that may hold that dependency, rather than the
  dependency being added to reach the test

### Requirement: Every test runs on every target its module declares

Logic tests SHALL live in `commonTest`, so they compile and run on every target the module declares
— JVM as the fast loop and `iosSimulatorArm64` as the target that ships. A platform-specific test
source set SHALL hold only what that platform's toolchain makes unrunnable elsewhere, and where a
sibling target has an equivalent implementation the two SHALL meet in a shared contract hosted in
`:test:world`'s `commonMain` (`LedgerStoreContract`, `DownloadStoreContract`).

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

### Requirement: The app shells are wiring-only and untested

No `:app:*` module — `:app:ios`, `:app:ios:extension`, `:app:ios:forge`, `:app:desktop` — SHALL
declare a test source set. Behaviour that warrants coverage SHALL be relocated into `:domain` or an
adapter module rather than covered in place.

The guarantee this rule rests on is **"nothing worth testing is there"**, produced by the shell
gates (`detektAppShell`, `KotlinShellGuardTest`, `SwiftShellGuardTest` — capability
`architecture-guards`), which forbid **decisions** in shell source. Their scope is the two
live-core shells — `:app:ios` and `:app:ios:extension`, plus any source contributed into them. The
other two `:app:*` modules are unscanned, and are untested for different reasons that SHALL NOT be
conflated with the gated one: `:app:ios:forge` links no live graph at all, and `:app:desktop`'s
harness panes are the named test-equipment zone, exempt from the shell laws (`module-architecture`)
and exercised non-gatingly through `:test:harness-driver`.

The rule's **residual risk SHALL be stated wherever it is relied upon**: the shell gates do not
catch **mis-transcription**. A zero-conditional forwarding that names the wrong collaborator holds
no decision, passes every gate, compiles, and is wrong. "Untested" here is a project choice about
what may live in a shell, not a claim that shell code is untestable.

#### Scenario: A test file is added under a shell module

- **WHEN** a test source set appears in any `:app:*` module
- **THEN** the behaviour it covers is relocated into `:domain` or an adapter, and the test moves
  with it

#### Scenario: A shell forwarding is wrong but decides nothing

- **WHEN** a shell forwards a platform callback to the wrong collaborator, with no conditional
- **THEN** every shell gate passes and the build is green; the defect is not covered by this rule,
  and a capability relying on shell correctness states that gap rather than assuming coverage

### Requirement: The seam-to-UI-state integration surface

`:test:integration` SHALL compose the **real** core — the same `snapSyncApp`/`uploadCore` the device
shells call (`module-architecture`, "One shared composition") — over `:test:world`'s rigged
`:adapter:generic:fake` ports, drive the real flows and commands, and assert **world outcomes**:
objects landed in the backend store, ledger rows reaching `COMPLETED`, foreign photos imported into
the in-memory gallery. Where the seam under test reaches presentation, it SHALL **also** assert the
projected `UiState`.

Asserting `UiState` is required of the seams that reach it, not of every test in the module: a
selection-policy exclusion is proved by the absence of bytes, of a ledger row, and of a manifest
entry, none of which is a `UiState`. The module's suites SHALL run on JVM and `iosSimulatorArm64`.
What `:test:world` provides is owned by `harness-world-model`.

#### Scenario: A seam that reaches presentation

- **WHEN** an integration test drives a seam whose effect is projected into `UiState`
- **THEN** it asserts both the world outcome and the projected `UiState`

#### Scenario: A seam that does not reach presentation

- **WHEN** an integration test drives a seam with no presentation projection
- **THEN** it asserts the world outcomes alone, and that is the complete assertion

### Requirement: Fake-driven feature tests live in the fake module

Feature tests that drive `:domain` subjects through the honest in-memory port implementations SHALL
live in `:adapter:generic:fake`'s own `commonTest`. `:domain`'s test source set cannot reach those
fakes: `:adapter:generic:fake` depends on `:domain`, so a test edge back from `:domain` is a project
dependency cycle, and a test source set cannot be depended on across modules at all — which is the
same constraint that puts the shared storage contracts in `:test:world`'s `commonMain`
(`harness-world-model`).

`:domain`'s `commonTest` SHALL therefore hold only tests standing on pure functions or hand-written
local doubles.

Two consequences SHALL be stated rather than discovered: a feature's tests may be split across two
modules, so a reader looking for them must look in both; and `:adapter:generic:fake`'s `commonTest`
is a **test host**, outside the fake-honesty surface — that gate scans main source sets only
(`architecture-guards`, "The fake-honesty gate").

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

- **`:test:world`** — the controllable in-memory world and the shared storage-seam contracts,
  consumed by both `:app:desktop` and `:test:integration` (capability `harness-world-model`).
- **`:test:integration`** — the seam-to-UI-state surface above.
- **`:test:architecture`** — JVM guards over the repository's own text (capability
  `architecture-guards`, which owns what each guard checks).
- **`:test:harness-driver`** and **`:test:rig`** — non-gating dev infrastructure with no spec,
  contained at compile time.

No production module's **main** source set SHALL depend on a `:test:*` module.

#### Scenario: A production module reaches for test infrastructure

- **WHEN** a production module's main source set declares a dependency on a `:test:*` module
- **THEN** the dependency is rejected; a test source set extending a shared contract is a test
  compilation and introduces no production edge

### Requirement: The world hosts feature tests over the real stack

`:test:world`'s own `commonTest` SHALL host the tests that drive real features against the composed
world without projecting to presentation — the upload cycle, the sync engine, the leave cascade, the
manifest, the mini-edge's own fidelity. This is a distinct tier from the seam-to-UI-state surface,
and it is where the world's faithfulness to the backend it models is pinned.

Stating the tier is load-bearing: it was undocumented, so a reader looking for a feature's coverage
had no reason to look there and could conclude the feature was untested.

#### Scenario: A feature is exercised against the world with no UI projection

- **WHEN** a feature's behaviour is asserted over the composed world with no `UiState` involved
- **THEN** the test belongs in `:test:world`'s `commonTest`, not in `:test:integration`

### Requirement: The canonical check and its Kotlin/Native half

`./gradlew build` SHALL be the canonical check: it compiles every target and runs every JVM test and
every gate, and it requires no display. `./gradlew iosSimulatorArm64Test` SHALL run the same shared
sources compiled to Kotlin/Native, and is the **only** place Kotlin/Native-only breakage is caught —
breakage the JVM accepts. Both SHALL be required merge gates; which jobs run them, on what runner,
is owned by `ci-build` and `ios-ci`.

`./gradlew compileIosMainKotlinMetadata` SHALL be the Linux-runnable proxy for the iOS source sets.
It is a compile, not a test, and SHALL NOT be described as coverage.

#### Scenario: A change compiles on the JVM but not on Native

- **WHEN** shared source uses a construct the JVM accepts and Kotlin/Native rejects
- **THEN** the simulator run fails and the JVM build does not, which is the reason both gates are
  required

### Requirement: Device-only behaviour is measured, not mocked

Behaviour reachable only on hardware SHALL NOT be asserted by unit tests: PhotoKit's
cloud-identifier and upload-job subsystems, App Attest, background `URLSession` reattachment, APNs
delivery, and the limited-access alert's arming. It SHALL be established instead by a recorded
on-device measurement carrying its expiry trigger (`module-architecture`, "Necessity claims carry
forcing proofs"), and the simulator smoke tests mark where the testable surface stops.

A test that appears to cover such behaviour asserts a copy of the platform's constants against
itself, which is why the platform-vocabulary pin reads the declared vocabulary from the
Kotlin/Native distribution instead (`architecture-guards`).

#### Scenario: A device-only claim is asserted in a unit test

- **WHEN** a test asserts behaviour that only hardware exhibits
- **THEN** it is replaced by a recorded measurement with a named expiry trigger, because the test
  can only restate its own fixture
