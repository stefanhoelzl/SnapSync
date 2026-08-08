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

Decision record: `changes/archive/2026-07-17-establish-target-architecture` (interview + four adversarial reviews + a
40-claim necessity audit; user decisions D4/D8/D10/D11 recorded with their evidence).
## Requirements
### Requirement: The module set withholds; packages organize
The system SHALL consist of exactly these production modules, each existing because it withholds a
third-party or platform dependency by compile error: `:domain` (one module; zero `project()`
dependencies; no `iosMain` source directory), `:ui:presentation`, `:ui:screens`,
`:ui:components` (the only module that may depend on Material 3), `:adapter:ios:ext-safe`,
`:adapter:ios:app-only`, `:adapter:generic:app`, `:adapter:generic:fake`, `:app:ios`,
`:app:ios:extension`, and `:app:desktop`. The adapter tree SHALL be uniformly two-level —
`adapter:<platform-axis>:<linkage-leaf>` — with each platform-axis prefix (`adapter/ios/`,
`adapter/generic/`) a pure path grouping that is not itself a module (no build file: a prefix
module would withhold nothing). All finer structure SHALL be packages whose boundaries are
enforced by derived text gates, not modules. Test-only modules (`:test:*`) and the named
test-equipment zone (harness panels, world inspector) are exempt from production-module laws.

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
technology, placed by linkage. The linkage leaf's vocabulary is per platform axis, deliberately:
on the ios axis the leaves encode PROCESS linkage (`ext-safe` may link into the extension
process, `app-only` must not), on the generic axis they encode SHIPPABILITY (`app` links into the
shipped app **and** extension binaries; `fake` never ships) — each axis names the question that
discriminates its own leaves. Adapters MAY branch on technology vocabulary. Pure logic SHALL NOT
be a port. Backend access SHALL be split into need-named ports (one adapter may implement many).

The composition bundles (`AppPorts`, `UploadPorts`) MAY carry **function-typed** fields, but only
for coordination **within** the core — a call back into the core's own machinery, which is how
`compose/` hands `flow/` its collaborators without `flow/` naming a port (see "Zones inside the
core"). A field whose invocation reaches **out of the process** SHALL be a port type, never a
function type, however short its implementation. An inline lambda in a composition root that reads
a platform value or performs a platform effect is an adapter written in the wrong place, and is a
violation even where a port for the same need already exists.

An adapter may be platform-specific; the port it implements SHALL NOT be. An adapter is therefore
free to name platform types, constants and error codes internally, and SHALL do so rather than
hoisting them inward: a platform's magic values, ABI integers, identifier grammars and error-domain
tables SHALL NOT appear in `model/`, `ports/` or `feature/`, even where they cross no port and even
where the platform-free zones are the cheaper place to unit-test them. Where an adapter can report a
platform-independent fact, it SHALL report that fact rather than the platform's encoding of it.

#### Scenario: Naming survives a second platform
- **WHEN** a port is proposed whose name describes an Apple technology rather than the
  application's need
- **THEN** the port is renamed for the need before it is added to `ports/`

#### Scenario: Core purity is closed by default
- **WHEN** a new technology library is used anywhere in `:domain` or `:ui:presentation`
- **THEN** the per-zone allowlist gate fails until the library is consciously allowlisted, with
  no per-technology gate edits required

#### Scenario: A composition bundle gains a function-typed field that leaves the process
- **WHEN** a field is added to `AppPorts` or `UploadPorts` whose invocation reads a platform value,
  performs a platform effect, or crosses the network
- **THEN** the seam gate fails until the field is either given a port type or added to the pinned
  inventory with a stated reason it is not a port

#### Scenario: A port exists and the composition reaches past it
- **WHEN** a composition root supplies a value inline from a platform API for which a port and an
  adapter already exist
- **THEN** the composition is corrected to inject the existing port, because a bypassed seam is
  indistinguishable from an absent one to everything downstream

#### Scenario: A platform constant is named inside a platform-free zone
- **WHEN** an Apple identifier, error-domain string, or platform enum's raw value appears in the
  code (not the documentation) of `model/`, `ports/` or `feature/`
- **THEN** the platform-identifier gate fails, and the declaration moves into the adapter that
  already holds the platform object

#### Scenario: A translation is hoisted inward to reach the faster test loop
- **WHEN** a platform-to-neutral translation is placed in `model/` so it can be exercised in
  `commonTest` rather than in the adapter that owns its inputs
- **THEN** the placement is rejected: the translation belongs beside its inputs, where a test can
  assert against the platform's own symbols instead of against a copy of the constant

### Requirement: State and authority
`:domain` SHALL contain no top-level or global mutable state (no allowlist). Instance state in
the core SHALL be limited to derived caches (projections recomputable from ports) and
coordination primitives. Authority SHALL live behind ports. The test: after process kill,
relaunch, and recomposition from ports, every fact SHALL be recoverable from durable stores or
from the external system through its port, keyed only by identifiers the external system
persisted. Which thread a port call runs on SHALL NOT be a port implementation's concern — it is
fixed by the composition (see "Dispatcher lanes are fixed by the composition"), and no port call
may assume the caller's thread.

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

### Requirement: A trigger flow never outlives its own run

A `flow/` class SHALL NOT declare a `CoroutineScope` parameter or field, and every lambda parameter it
accepts whose return type is `Unit` SHALL be `suspend`. Its entry point SHALL be `suspend` and SHALL
return only when the work it coordinates has finished; concurrency inside a flow SHALL be expressed with
structured concurrency so that fan-out is preserved while the entry point still awaits its children.

The rule is drawn at `Unit`-returning lambdas because those are the only ones that can detach: a lambda
returning a value must produce it synchronously, so a flow's reads (`() -> String?`, `() -> Boolean`) are
unaffected. It deliberately covers effects that happen to be synchronous today — a `BGTaskScheduler`
submit does not suspend — because the flow cannot see which of its `Unit` lambdas the composition backed
with a detached launch, and neither can a gate.

A flow exists to order the work an OS callback triggered, and its caller is a shell that must report
completion back to the operating system. A flow that detaches work returns before that work starts, so
the shell's report is a false statement about work it never observed — and the platform is entitled to
suspend the process on the strength of it.

Both doors matter. Removing the scope alone is insufficient: a non-suspend `() -> Unit` effect lambda
can only detach, so whatever the composition places behind it escapes the flow's lifetime while the
zone gate stays green.

#### Scenario: A flow declares a scope

- **WHEN** a `flow/` class gains a `CoroutineScope` constructor parameter
- **THEN** the zone gate fails, naming the file

#### Scenario: A flow takes a non-suspend Unit lambda

- **WHEN** a `flow/` class gains a lambda parameter returning `Unit` that is not `suspend`
- **THEN** the zone gate fails, because whatever the composition puts behind it can only be detached

#### Scenario: A flow's value-returning reads are unaffected

- **WHEN** a `flow/` class declares a lambda parameter returning a value rather than `Unit`
- **THEN** the gate passes, because a lambda that must produce a value cannot detach its work

#### Scenario: Fan-out is preserved without detachment

- **WHEN** a flow coordinates several independent effects that previously ran concurrently
- **THEN** they still run concurrently, and the flow's entry point returns only once all of them have
  finished

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

### Requirement: Absence is never silent

A seam that can answer "nothing" SHALL distinguish *nothing* from *could not tell* **wherever the
two have different consequences**. Where they are deliberately collapsed, the collapse SHALL name
the consequence that makes it safe **for every cause it absorbs** — not only for the cause its
author had in mind. An entry point SHALL never collapse into silence: a driver's arrival and its
outcome are recorded before and after any decision, because a lost trigger is invisible and
unfixable while a spurious log line is harmless and visible (the same asymmetry the
`photo-selection-policy` capability uses to admit on doubt).

This law describes existing practice. `ConfigFileRead` admits only the not-found error class as
absence and defers on every other failure; `ConfigRead` carries distinct sentinels so a device log
can tell two unreadables apart; `SecureStoreRead` separates `Absent` from `Unavailable` and
`readExisting` throws on the latter rather than returning null; `JoinLoad` keeps `NotFound`
distinguishable from `Failed`; `SwitchDecision` returns a named answer where a null would do. The
law names the rule those seams already follow so that a violation is a defect rather than a
discovery.

Separating the two answers is a requirement on the seam's **shape**, not on what it carries with
them. `SecureStoreRead.Unavailable` carries an opaque adapter-formatted diagnostic rather than the
platform's error code, precisely so that no caller can classify it: the three-state shape is what
every decision reads, and the diagnostic exists only to reach a device log. A seam SHALL NOT be
read as satisfying this law by carrying a rich failure payload while collapsing the answers, nor as
violating it by carrying a poor one while keeping them apart.

The test is **consequence asymmetry, not nullability**. A nullable return is not itself a
violation: `DiscoveryStore.loadToken` collapses absent and unreadable correctly, because a cold
start with no cursor re-enumerates the whole library and the ledger makes that harmless, and it
says so. The violation is a collapse whose stated consequence does not cover every cause it
absorbs, or a collapse with no stated consequence at all.

The law is enforced mechanically at the two seams where enforcement is possible — platform entry
points and the `ports/` boundary (capability `architecture-guards`) — and is otherwise a design
discipline, like *Necessity claims carry forcing proofs*.

The law governs **absence**, not staleness. A seam that returns a confidently wrong non-null value
is a different defect and is out of its scope.

#### Scenario: A seam collapses two answers with different consequences
- **WHEN** a seam returns a single "nothing" value for both a genuine absence and a failure to
  determine, and the two lead to different downstream behavior
- **THEN** the collapse is a defect: the answers are separated into distinct values, or the
  collapse is retained with the consequence that makes it safe stated for every cause it absorbs

#### Scenario: A justified collapse absorbs an unconsidered cause
- **WHEN** a collapse carries a written justification that holds for one cause, and a second cause
  reaching the same collapse has a materially different consequence
- **THEN** the justification is incomplete and the seam is corrected — either by separating that
  cause or by recording it, never by leaving it silent

#### Scenario: An entry point declines to act
- **WHEN** a platform entry point receives a driver and decides to do nothing with it
- **THEN** the reason is recorded, so an absent downstream effect is never ambiguous between "the
  platform never called" and "the call was discarded"

#### Scenario: A nullable seam is not automatically a violation
- **WHEN** a seam returns a nullable value and the absent and undeterminable cases lead to the same
  downstream behavior
- **THEN** the collapse is legitimate, and the requirement on it is that the shared consequence is
  stated

#### Scenario: A three-state read is narrowed to a platform-free failure payload
- **WHEN** a port's "could not tell" answer stops carrying the platform's error code and carries an
  opaque diagnostic instead
- **THEN** the law is still satisfied, because the separation the law requires is between the
  answers, not in what the failing one reports

### Requirement: Dispatcher lanes are fixed by the composition

Which thread work runs on SHALL be a property of the composition root, not of the adapter that
performs the work. The previous rule — that each synchronous-I/O port implementation owns its own
dispatcher hop — is withdrawn: its compliance test lives at the call site, in another module, and
often in another process, so the same adapter is correct in the extension and lethal in the app.
Measured compliance was 2 of 23 adapter files, and both compliant seams were written after an
incident rather than before one.

Three lanes SHALL exist, each with a stated purpose:

- the **main lane** is reserved for platform UI (UIKit presentation and the system prompts it
  drives) and SHALL carry nothing else;
- the **CPU lane** carries presentation-state reduction;
- the **composition lane** carries the live core's scope: blocking platform calls, network, and
  durable stores. It SHALL be a dispatcher of the composition's own, not a slice of the CPU lane,
  so that a blocked platform call cannot consume the pool that presentation-state reduction runs on.

The live core's composition scope SHALL NOT be UI-bound, in any binary that composes it — device
shell or harness alike. It SHALL be **serial**: the main thread it replaces is single-threaded, and
core code relies on that for mutual exclusion, so changing the thread SHALL NOT also change
concurrency semantics.

User-initiated commands SHALL declare their lane where they are built. The composition scope does
not govern them: the presentation container launches an intent on an unconfined dispatcher, so a
command's synchronous prefix runs on the thread that fired it. Every command SHALL therefore be
built through a lane-declaring decorator, and no decorator SHALL supply a default lane.

A dispatcher hop inside an adapter is permitted, but its meaning is **throughput** — allowing that
work to proceed concurrently with other work on the serial composition scope — and never safety.
A hop SHALL NOT be justified by keeping work off the main thread, because the composition already
does that.

This law accepts a cost it did not previously state: **correctness became non-local.** Whether
calling an adapter is safe is no longer readable from the adapter — it is a property of where the
adapter was composed. The main-lane containment gate bounds that cost (the main lane is unreachable
by default and reachable only through a reviewed allowlist), but a reader's default assumption will
be main-safety, and nothing corrects them at the point of reading. The law is nonetheless preferred
to per-adapter main-safety, whose enforcement mechanism was code review and which code review
demonstrably did not enforce.

**Expiry trigger.** The reasoning above is iOS-shaped in two ways that a second platform would not
inherit: the "2 of 23" measurement is of this codebase's iOS adapters, and the argument that the
compliance test "lives in another process" depends on there being two processes (the app and the
upload extension). A platform whose background work runs in the app's own process — an Android
`WorkManager` worker, for instance — SHALL have this law re-derived rather than inherited. The
absence of a public `Dispatchers.IO` on Kotlin/Native, which is why the composition lane is a
dedicated thread rather than a slice of the CPU lane, carries its own expiry trigger already: a
coroutines release that publishes it.

#### Scenario: A blocking adapter is written with no dispatcher hop
- **WHEN** a new adapter performs a synchronous platform call and hops nowhere
- **THEN** it runs on the composition's I/O lane, off the main thread, because of where it was
  composed rather than what its author remembered

#### Scenario: A composition scope is bound to the UI thread
- **WHEN** any binary composes the live core on a UI-bound scope
- **THEN** the law is violated, whether that binary is the device shell or a harness

#### Scenario: A command is added without a lane
- **WHEN** a command is added to the user-command bundle without a lane-declaring decorator
- **THEN** it does not compile, because no decorator supplies a default

#### Scenario: A hop is justified as safety
- **WHEN** an adapter's dispatcher hop is documented as keeping work off the main thread
- **THEN** the justification is wrong and is corrected to name the concurrency it buys, or the hop
  is removed

#### Scenario: A hop cites the withdrawn per-adapter rule
- **WHEN** an adapter's dispatcher hop is justified as the port implementation "owning" its hop
  because only it knows the call blocks
- **THEN** the justification cites a rule this requirement withdrew, and is corrected or the hop is
  removed — a comment claiming this spec's authority for the withdrawn rule is the worst form,
  because a reader who follows the citation finds a document that contradicts it

#### Scenario: A second platform inherits the lane reasoning
- **WHEN** a platform is added whose background work runs in the app's own process
- **THEN** the three-lane arrangement is re-derived against that platform's facts rather than
  carried over, because the measurement and the two-process argument behind it are iOS-shaped

### Requirement: A platform-capability claim is settled by a compile, not by a symbol table

A necessity claim about what a target platform provides SHALL be settled by compiling against the
API, not by inspecting a published artifact's symbols. A klib, jar, or framework records what
**ships**; it does not record what is **callable**. The two differ, and the difference is invisible
to inspection: `Dispatchers.IO` appears in the Kotlin/Native coroutines klib — as `IO`, `<get-IO>`
and `DefaultIoScheduler` — while being `internal`, so a design built on the symbol table's evidence
had to be withdrawn at the first compile.

This does not replace the existing rule that necessity claims carry forcing proofs; it names the
form of evidence that does not qualify.

#### Scenario: A capability is inferred from a published artifact
- **WHEN** a design depends on a platform API whose availability was established by reading symbols
  out of an artifact
- **THEN** the claim is re-established by compiling against that API before any other work depends
  on it, and the design records the compile rather than the symbol

#### Scenario: A claim of absence is stated more precisely than it was found
- **WHEN** a comment records that a platform lacks a facility
- **THEN** it states what is actually absent — a public API, a target, a version — rather than the
  facility as a whole, so a later reader can tell which change would falsify it

