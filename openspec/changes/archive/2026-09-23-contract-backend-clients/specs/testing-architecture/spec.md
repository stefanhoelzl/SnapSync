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
  the repository's own text; `:adapter:generic:app`'s `jvmTest` also holds the backend contracts' live
  bindings, because they launch the real backend as a local process, which a Kotlin/Native test
  executable under `simctl` cannot do. The forgo costs nothing: the clients under contract are
  `commonMain` code, identical on every target, and their Kotlin/Native compilation is covered by that
  module's `commonTest`. Each SHALL name the coverage it forgoes where it is declared.

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

### Requirement: The canonical check and its Kotlin/Native half

`./gradlew build` SHALL be the canonical check: it compiles every target and runs every JVM test and
every gate, and it requires no display. It SHALL require `deno` on `PATH`: the backend contracts' live
bindings run the real `api/` edge locally, offline, against a filesystem store and with network access
limited to loopback. A missing `deno` SHALL fail those bindings loudly, naming the prerequisite, and SHALL
NOT turn their clauses into `NotRunHere`. A task running those bindings SHALL declare the backend's
sources as inputs, so a backend-only change re-runs them instead of being up-to-date. `./gradlew iosSimulatorArm64Test` SHALL run the same shared
sources compiled to Kotlin/Native, and is the **only** place Kotlin/Native-only breakage is caught —
breakage the JVM accepts. Both SHALL be required merge gates; which jobs run them, on what runner,
is owned by `ci-build` and `ios-ci`.

`./gradlew compileIosMainKotlinMetadata` SHALL be the Linux-runnable proxy for the iOS source sets.
It is a compile, not a test, and SHALL NOT be described as coverage.

#### Scenario: A change compiles on the JVM but not on Native

- **WHEN** shared source uses a construct the JVM accepts and Kotlin/Native rejects
- **THEN** the simulator run fails and the JVM build does not, which is the reason both gates are
  required

#### Scenario: Deno is not installed

- **WHEN** `./gradlew build` runs on a machine with no `deno` on `PATH`
- **THEN** the live backend bindings fail with a message naming the missing prerequisite, and no backend
  clause reads `NotRunHere` because of it

#### Scenario: Only the backend changed

- **WHEN** a change touches `api/src` and no Kotlin source
- **THEN** the task running the live backend bindings is not up-to-date and runs the contracts again
