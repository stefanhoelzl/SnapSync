# module-architecture Specification

## Purpose

The target module graph and the laws that govern it — the contract every placement decision is
checked against. The previous graph's names encoded no rule (`:domain:` meant "shared",
`:capability:` labeled both use-cases and vocabulary), so nothing contradicted a wrong edge: the
documented spine was inverted in two places, one declared edge was never imported, and the
untested app shell became the most-churned, most-defective region of the codebase. This spec
arranges the load-bearing rules to be compile errors where the build system can express them and
derived text gates where it cannot, and requires every "the platform forces X" claim to carry a
forcing proof instead of inheriting the current code's shape.

The laws also serve the tooling built on them: flows are transcribable into sequence diagrams and
compositions into wiring matrices precisely because the laws forbid the constructs that would make
derivation unfaithful.

Decision record: `establish-target-architecture` (interview + four adversarial reviews + a
40-claim necessity audit; user decisions D4/D8/D10/D11 recorded with their evidence).

## Requirements

### Requirement: The module set withholds; packages organize
The system SHALL consist of exactly these production modules, each existing because it withholds a
third-party or platform dependency by compile error: `:domain` (one module; zero `project()`
dependencies; no `iosMain` source directory), `:ui:presentation`, `:ui:screens`,
`:ui:components` (the only module that may depend on Material 3), `:adapter:ios:ext-safe`,
`:adapter:ios:app-only`, `:adapter:generic`, `:adapter:fake`, `:app:ios`, `:app:ios:extension`,
and `:app:desktop`. All finer structure SHALL be packages whose boundaries are enforced by
derived text gates, not modules. Test-only modules (`:test:*`) and the named test-equipment zone
(harness panels, world inspector) are exempt from production-module laws.

#### Scenario: A structural boundary that withholds nothing is rejected
- **WHEN** a new module is proposed whose dependency block withholds no third-party or platform
  dependency from any consumer
- **THEN** the structure SHALL be a package with a gate instead, and the module-set gate fails
  until the module list is consciously amended

#### Scenario: The core cannot reach a platform
- **WHEN** any file under `:domain` references a platform API or a non-allowlisted library
- **THEN** compilation fails (unresolvable symbol), because `:domain` declares no project
  dependencies and only the per-zone allowlisted libraries

### Requirement: Zones inside the core
`:domain` SHALL contain exactly five package zones with these import laws, enforced by
source-text gates with derived scopes: `model/` (vocabulary, domain services, pure codecs;
imports nothing project-internal), `ports/` (every port interface; imports `model/` only),
`feature/` (imports `model/` and `ports/` only; no feature references a sibling feature),
`flow/` (imports `model/` and `feature/` only; never `ports/`), and `compose/` (may import all
of `:domain`; holds the shared composition, flow decorators, and port-state-transition
subscriptions).

#### Scenario: A feature references a sibling feature
- **WHEN** any file under `feature/<a>/` contains a reference (imported or fully qualified) to a
  declaration under `feature/<b>/`
- **THEN** the feature-blindness gate fails, naming both packages

#### Scenario: A flow reaches a port
- **WHEN** any file under `flow/` contains a reference to a declaration under `ports/`
- **THEN** the flow-zone gate fails

### Requirement: Ports are the I/O boundary named for the need
The system SHALL access anything touching an external system (time, timezone, files, network,
environment, and platform facilities included) only through a port interface declared in `ports/`,
named for the need it serves (the name must remain correct if a second platform ships), never
for the technology satisfying it. Adapter modules SHALL hold implementations only, named for the
technology, placed by linkage (extension-safe vs app-only vs generic vs fake), and MAY branch on
technology vocabulary. Pure logic SHALL NOT be a port. Backend access SHALL be split into
need-named ports (one adapter may implement many).

#### Scenario: Naming survives a second platform
- **WHEN** a port is proposed whose name describes an Apple technology rather than the
  application's need
- **THEN** the port is renamed for the need before it is added to `ports/`

#### Scenario: Core purity is closed by default
- **WHEN** a new technology library is used anywhere in `:domain` or `:ui:presentation`
- **THEN** the per-zone allowlist gate fails until the library is consciously allowlisted, with
  no per-technology gate edits required

### Requirement: State and authority
`:domain` SHALL contain no top-level or global mutable state (no allowlist). Instance state in
the core SHALL be limited to derived caches (projections recomputable from ports) and
coordination primitives. Authority SHALL live behind ports. The test: after process kill,
relaunch, and recomposition from ports, every fact SHALL be recoverable from durable stores or
from the external system through its port, keyed only by identifiers the external system
persisted. Synchronous-I/O port implementations SHALL own their dispatcher hop; no port call may
assume the caller's thread.

#### Scenario: A mutable global appears in the core
- **WHEN** any top-level mutable state is declared under `:domain`
- **THEN** the core-purity gate fails, with no exception mechanism

#### Scenario: Authoritative state outside a port
- **WHEN** a core object holds state whose loss on process death loses a fact no port can restore
- **THEN** the design is corrected so the authority moves behind a port (review criterion,
  recorded in the law text)

### Requirement: Rules in features, order in flows
Business rules SHALL live in features; cross-feature ordering SHALL live in flows, which
coordinate and never decide. Features SHALL be mutually blind, coordinating only through durable
state behind shared ports with exactly one writer feature per durable port (per key where one
technology backs several stores); shared config SHALL be written whole by its one writer, and no
field of shared state may encode a request to another feature. Recovery and reconciliation
decisions SHALL be feature rules over port-exposed facts, never adapter logic.

#### Scenario: An ordering rule hides in a feature
- **WHEN** review finds a feature method whose only purpose is to sequence effects on behalf of a
  flow that could not express a branch
- **THEN** the branch is restored to the flow using the transcriber grammar's sealed-result form,
  or the rule is named and kept in the feature as a rule

#### Scenario: Two features write one durable port
- **WHEN** a second feature gains write access to a durable port (or key) that already has a
  writer
- **THEN** the design is corrected so one feature owns the write path

### Requirement: Commands cross one door
Every command SHALL cross `flow/`: user taps, OS callbacks, and port-state transitions are the
three driver kinds. `flow/` SHALL define command types; instances SHALL be built, decorated, and
(under a forge directive) substituted only in `compose/`; `:ui:presentation` SHALL receive the
command bundle by constructor and SHALL NOT reference flow callables directly. Reads SHALL NOT
cross flow: features expose read-model projections (state flows) that presentation observes
directly; presentation SHALL NOT invoke feature commands. Adapter outbound callbacks SHALL be
declared on the port and satisfied only by compose-built lambdas whose body is a single flow
command call. Port-state-transition subscriptions SHALL be installed in `compose/` with their
transition semantics tested in a feature. Flows are commands, not sessions: multi-step
interactions are presentation-owned choreography of one-shot commands, and interaction state
dies with the UI. The trigger inventory SHALL be derived from entry points, never hand-enumerated.

#### Scenario: The UI bypasses the door
- **WHEN** `:ui:presentation` references a feature command (suspend function) or a flow callable
  directly
- **THEN** the presentation gate fails; only feature read-model types and the injected command
  bundle are legal

#### Scenario: An OS event bypasses the door
- **WHEN** an adapter's outbound callback is wired to call a feature directly
- **THEN** the wiring is corrected so the compose-built lambda calls a flow command

### Requirement: One shared composition
Every binary that assembles the live core SHALL call the shared composition (`snapSyncApp` for
the app graph, `uploadCore` for the extension's strict subset bundle); there SHALL be no second
wiring. The composition functions SHALL receive a `CoroutineScope`. The wiring graph SHALL NOT
be unit-tested (it is smoke-tested end to end by the world harness and integration tests over
fake ports); composition selection SHALL be a pure, unit-tested function from parsed launch
directives and OS facts to a sealed composition mode, with `composeRoot` switching once on the
sealed type and invoking only the selected shell-supplied adapter thunks. The forge composition
is the one named non-core composition, with its own non-vacuity gate.

#### Scenario: The harness cannot drift from production
- **WHEN** the world harness and the device binaries compose the core
- **THEN** they execute the same composition function over different port implementations, so a
  wiring difference is impossible rather than undetected

#### Scenario: A new launch directive is added
- **WHEN** a new dev/test trigger is introduced
- **THEN** the sealed composition-mode resolver fails to compile until the mode handles it, and
  the resolver's precedence rules (forge excludes live-stack boot) are unit-tested

### Requirement: Shells are wiring only
`:app:*` Kotlin SHALL contain zero conditionals (enforced by a complexity gate); shells construct
adapters, supply thunks, and forward entry points. Swift SHALL be a transcriber, not a decider:
it forwards raw ObjC-visible inputs whole, constructs no domain values, and branches on nothing;
where the OS offers an ObjC-visible surface, Kotlin owns the trigger directly. Swift decision
keywords SHALL be pinned to an explicit list of irreducible occurrences, each justified by a
Swift-only API and carrying its forcing proof.

#### Scenario: A decision appears in a shell
- **WHEN** an `if`/`when` beyond the pinned forms appears in `:app:*` Kotlin or a decision
  keyword beyond the pinned list appears in the Swift shells
- **THEN** the respective gate fails, and the fix moves the decision into a tested zone

### Requirement: Necessity claims carry forcing proofs
Any claim that the platform forces a design ("must", "cannot", "only way") SHALL cite an API
contract, an on-device measurement, or a vendor document — never the current code's shape — and
SHALL name the expiry trigger that would dissolve it. Gates that pin exceptions SHALL carry the
forcing proof in their failure message.

#### Scenario: An inherited necessity is challenged
- **WHEN** a necessity claim's only citation is existing code
- **THEN** the claim is re-derived from the underlying need before the design that rests on it is
  accepted

#### Scenario: A forcing proof expires
- **WHEN** the named expiry trigger occurs (for example, a new OS API version)
- **THEN** the pinned exception is re-evaluated rather than renewed by default
