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

The module set was restated as **three groups** — withholding, containment, support — in
`changes/archive/2026-08-27-account-for-every-module`, because the single enumeration could only
express one justification and therefore accounted for neither `:app:ios:forge` (which exists to be
contained, not to withhold) nor `:tools:diagrams`. That change also moved the module-set gate's
expected value out of the guard and into this requirement, so the enumeration below is now read by
`ModuleSetTest` at test runtime rather than copied into it.

Decision record for the inbound ports and the shell as their driving adapter: `changes/archive/2026-09-22-shell-as-driving-adapter`.
Decision record for its seam, failure, state and concurrency rules: `changes/archive/2026-09-23-harden-seam-bug-classes`.

Decision record for the control protocol's client and the real backend as support modules: `changes/archive/2026-09-23-add-rig-jvm-host`.
## Requirements
### Requirement: The module set withholds; packages organize
The system SHALL consist of exactly the modules enumerated below, and **every** module the build
declares SHALL appear in exactly one group. A group names the law that justifies its members'
existence; a module justified by no law is a package with a derived text gate instead.

- **Withholding modules** — each exists because it withholds a dependency from its consumers by compile
  error. The withheld dependency is usually a third-party or platform one; it MAY also be **another zone
  of the core**, where a module boundary is the only construction that makes the zone edge unresolvable
  rather than merely forbidden. Members: `:domain:model`, `:domain:ports`, `:domain:feature`,
  `:domain:flow`, `:domain:compose` (the core's zones, each depending only along the permitted zone edge
  and only via `implementation()`, so no zone leaks transitively; no `iosMain` source directory anywhere
  in the tree), `:ui:presentation`, `:ui:screens`, `:ui:components` (the only module
  that may depend on Material 3), `:adapter:ios:ext-safe`, `:adapter:ios:app-only`,
  `:adapter:generic:app`, `:adapter:generic:fake`, `:app:ios`, `:app:ios:extension`, `:app:desktop`.
- **Contained modules** — each exists so that something is absent from a production build, governed
  by "A build-time-only module is contained by compilation, not by a runtime check": `:app:ios:forge`
  (its own binary target, linked under `-Psnapsync.forge`), `:test:rig` (contributes its own call
  site into the iOS app shell, linked under `-Psnapsync.rig`; its JVM target, the control channel's JVM
  host, links into no shipped-format binary and is consumed only by test equipment), `:test:contracts` (the port contracts,
  linked under `-Psnapsync.rig` into the app, into the upload extension, and into the rig-gated source sets of
  the two iOS adapter modules, the extension-safe one and the app-only one; it withholds the test-assertion library from every other main source set — it is the
  only module whose main code may assert). A contained module is grouped by the law that governs it,
  **not** by its name prefix: these three are the same species and the containment law describes
  exactly their shapes.
- **Support modules** — never linked into any shipped-format binary, and exempt from the
  production-module laws: `:test:world`, `:test:integration`, `:test:architecture`,
  `:test:harness-driver`, `:tools:diagrams`, `:test:edge` (the real backend served as a local process for
  JVM tests, JVM-only), `:test:control` (the typed client of the control channel's protocol, JVM-only).
  The client depends on the control channel's JVM variant for the wire types, which stay in the contained
  channel module because the device build needs them without a client; the channel declares its own
  module dependencies with `implementation()`, so the client compiles against `model/` and `feature/`
  read-model types (through the presentation module) and never against `ports/`, `flow/` or `compose/` —
  that compile boundary is the whole of the client's read-model rule.

The core's zone split is the one place the withholding law is satisfied by an **internal** boundary, and it
is admitted for a stated reason: the zone edges were previously held by text gates that had to enumerate
the forms a violation could take, could not see generated source, and passed green when their scope
directory was renamed. A module boundary enumerates nothing and cannot be renamed into passivity. The cost
is bounded and was measured before the split: eighteen `internal` declarations across the whole core, none
in `ports/`, `flow/` or `compose/`.

The adapter tree SHALL be uniformly two-level — `adapter:<platform-axis>:<linkage-leaf>` — with each
platform-axis prefix (`adapter/ios/`, `adapter/generic/`) a pure path grouping that is not itself a
module (no build file: a prefix module would withhold nothing). The core's `domain/` prefix is likewise a
path grouping and not itself a module. All finer structure SHALL be packages
whose boundaries are enforced by derived text gates, not modules. The named test-equipment zone
(harness panels, world inspector) is likewise exempt from production-module laws.

The enumeration SHALL be exhaustive and SHALL NOT use wildcards: it is the expected value the
module-set gate compares the build's include set against (capability `architecture-guards`), and a
wildcard cannot be compared. Within a group, a backticked `:`-prefixed token **is** a membership
claim; prose in a group SHALL refer to another module by description rather than by its backticked
path, or it silently enrols that module in a second group. Adding a module therefore requires amending this requirement with the
group it joins and the argument for that group.

#### Scenario: A structural boundary that withholds nothing is rejected
- **WHEN** a new module is proposed whose dependency block withholds no third-party dependency, no
  platform dependency, and no zone of the core from any consumer, and which is neither compile-time
  contained nor never-shipped
- **THEN** the structure SHALL be a package with a gate instead, and the module-set gate fails
  until the module list is consciously amended

#### Scenario: A module exists to be contained rather than to withhold
- **WHEN** a module exists so that a surface is absent from a production build, rather than to
  withhold a dependency from other modules
- **THEN** it belongs to the contained group and is justified by the containment law, and it SHALL
  NOT be recorded as withholding a dependency it does not withhold

#### Scenario: A module reaches the build without reaching the spec
- **WHEN** an `include(...)` lands in `settings.gradle.kts` naming a module no group enumerates
- **THEN** the module-set gate fails, naming the module and the three groups it could join, and it
  cannot be satisfied by editing the gate

#### Scenario: The core cannot reach a platform
- **WHEN** any file in a core zone module references a platform API or a non-allowlisted library
- **THEN** compilation fails (unresolvable symbol), because a zone module declares only its permitted
  zone dependency and the per-zone allowlisted libraries

#### Scenario: A zone edge is crossed
- **WHEN** a file in one core zone module references a declaration from a zone its module does not depend
  on
- **THEN** the reference does not resolve and the build fails at compilation

#### Scenario: A zone dependency is exposed transitively
- **WHEN** a core zone module declares another zone with `api()` rather than `implementation()`
- **THEN** the boundary leaks to every downstream consumer, and the declaration is a defect the split
  exists to prevent

#### Scenario: A rig-gated source set in the app-only adapter module
- **WHEN** the app-only adapter module carries a source directory compiled only under `-Psnapsync.rig`, holding
  in-app contract bindings
- **THEN** the contracts module is linked there only under the same property, and a build without it
  contains neither

#### Scenario: A test client reaches for a port

- **WHEN** code in the control channel's client module, or its tests, names a type from `ports/`, `flow/` or
  `compose/`
- **THEN** compilation fails (unresolvable symbol), because no dependency on its compile path exports those
  zones

#### Scenario: The contracts module in the upload extension

- **WHEN** the upload extension is built with `-Psnapsync.rig`
- **THEN** the contracts module is linked into it together with the rig-gated source it runs through, and a
  build without the property contains neither

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

A **function type** is a seam with no name, no stated failure behaviour, no lane and no declared reach,
so it SHALL be used only where it has none of those to state: a callback **into** the core's own machinery
that cannot throw and does not leave the process. This holds for the composition bundles (`AppPorts`,
`UploadPorts`) and equally for every function-typed constructor parameter in `feature/` and `compose/`.
A seam SHALL instead be a port (or a need-named `fun interface` in `ports/`) when its invocation:

- reaches **out of the process** — reads a platform value, performs a platform effect, or crosses the
  network — however short its implementation, and even where the value it returns is stable after its
  first read (a lazily-read Keychain identity is a Keychain read);
- **can throw**, so that its failure contract is declared on the type rather than remembered by each caller;
- must run on a particular **lane**.

A value that is constant for the life of the process (a baked build setting, a bundle version) SHALL be
passed as a plain value, not as a thunk.

A composition bundle's function-typed field SHALL bind, in every composition, to the same thing — so a
field whose body is **core machinery** (a call into a flow, a feature, or another field of the core) SHALL
NOT be a bundle field at all: `compose/` builds it from the core it already holds, and no shell can bind it
differently. An inline lambda in a composition root that reads a platform value or performs a platform
effect is an adapter written in the wrong place, and is a violation even where a port for the same need
already exists.

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

#### Scenario: A function-typed seam leaves the process
- **WHEN** a function-typed field or constructor parameter in `compose/` or `feature/` is added whose
  invocation reads a platform value, performs a platform effect, or crosses the network
- **THEN** the seam gate fails until it is given a port type or pinned with a stated reason it is not a
  port

#### Scenario: A lazily-cached platform read is still a platform read
- **WHEN** a `() -> String` seam returns a device identity that the composition root reads from the
  Keychain on first use and memoizes
- **THEN** it is a port, because its first invocation — and every invocation after a failed one — crosses
  the process boundary and can throw

#### Scenario: A bundle field whose body is core machinery
- **WHEN** a shell binds an `AppPorts` field to a call into the composed core's own flow or feature
- **THEN** the field is removed from the bundle and built in `compose/`, so the world and the iOS root
  cannot bind it to different bodies

#### Scenario: A build constant is passed as a value
- **WHEN** a seam returns the running build's marketing version or its baked upload host
- **THEN** it is a plain value on the bundle, not a thunk

#### Scenario: A port exists and the composition reaches past it
- **WHEN** a composition root supplies a value inline from a platform API for which a port and an
  adapter already exist
- **THEN** the composition is corrected to inject the existing port, because a bypassed seam is
  indistinguishable from an absent one to everything downstream

### Requirement: State and authority
`:domain` SHALL contain no top-level or global mutable state (no allowlist). Instance state in
the core SHALL be limited to derived caches (projections recomputable from ports) and
coordination primitives. Authority SHALL live behind ports. The test: after process kill,
relaunch, and recomposition from ports, every fact SHALL be recoverable from durable stores or
from the external system through its port, keyed only by identifiers the external system
persisted. Which thread a port call runs on SHALL NOT be a port implementation's concern — it is
fixed by the composition (see "Dispatcher lanes are fixed by the composition"), and no port call
may assume the caller's thread.

The kill-test binds **port implementations as well as core objects**. An adapter satisfies "authority lives
behind ports" *vacuously* — the fact is behind a port, because the adapter is the port — while the port
cannot restore it. That reading is what let an upload tier hold every delivered completion in an
`ArrayList` for a later cycle to collect, with the law satisfied at review.

An entry point that receives a delivery the platform makes **once** SHALL persist it before returning, and
SHALL cite the proof that the delivery is once-only. The proof is an API contract, a vendor document, or a
measurement — never the current code — and it names which callbacks the obligation binds:
`URLSessionTask.State.completed` is documented as *"the task's delegate receives no further callbacks"*, so
a background-`URLSession` completion is bound by it; a `PHAssetResourceUploadJob` persists in the Photos
database until acknowledged, so it is not. Scheduling the write instead of performing it does not satisfy
this: after the callback returns the process's continued runtime is not guaranteed.

This obligation is the same sentence `diagnostic-logging` already carries at this boundary, with a different
object — a fact the platform delivered must not vanish without a **trace**, nor without a **record**.

Both halves of this requirement below the kill-test sentence are **review criteria**, not mechanical gates.
The population they apply to is small and derived (`architecture-guards`, the entry-point guard), but
whether a given callback holds a once-only fact is a judgement no syntactic rule separates from legitimate
coordination state. A per-callback declaration was considered and rejected: it records an assertion rather
than proving one, and a green check that only means "someone pasted a string" is worse than an honest
review criterion.

#### Scenario: A mutable global appears in the core
- **WHEN** any top-level mutable state is declared under `:domain`
- **THEN** the core-purity gate fails, with no exception mechanism

#### Scenario: Authoritative state outside a port
- **WHEN** a core object **or a port implementation** holds state whose loss on process death loses a fact
  no port can restore
- **THEN** the design is corrected so the fact is made durable, or becomes recoverable through a port
  (review criterion, recorded in the law text)

#### Scenario: A once-only delivery is held in memory
- **WHEN** a platform entry point receives a fact the platform will not deliver again and returns without
  persisting it
- **THEN** the design is rejected: the fact is unrecoverable after process death, whatever holds it
  (review criterion, recorded in the law text)

#### Scenario: A once-only claim without a proof
- **WHEN** a design asserts that a platform delivery is repeatable, or that it is not, citing only the
  current code
- **THEN** the claim is rejected until it cites an API contract, a vendor document, or a measurement, and
  names its expiry trigger

### Requirement: Rules in features, order in flows
Business rules SHALL live in features; cross-feature ordering SHALL live in flows, which
coordinate and never decide. Features SHALL be mutually blind, coordinating only through durable
state behind shared ports. Each **kind of write** to a durable port (per key where one technology
backs several stores) SHALL be owned by exactly one piece of code — one feature's use case or one
port's guarded write — and each such write SHALL be one transaction whose guard is in the statement,
so it is safe against any other write landing between its read and its write. How many **processes**
run that code at once is not an invariant: the upload ledger's record family is written by whichever
upload cycle runs, and on iOS ≥ 26.1 under a full grant both processes run one (`upload-lifecycle`,
"Both uploaders may run; an overlap is a duplicate, never a loss"; `sync-ledger`). Shared config SHALL
be written whole by its one writer, and no field of shared state may encode a request to another
feature. Recovery and reconciliation decisions SHALL be feature rules over port-exposed facts, never
adapter logic. Decision record: `changes/both-uploaders-active` (D1).

#### Scenario: An ordering rule hides in a feature
- **WHEN** review finds a feature method whose only purpose is to sequence effects on behalf of a
  flow that could not express a branch
- **THEN** the branch is restored to the flow using the transcriber grammar's sealed-result form,
  or the rule is named and kept in the feature as a rule

#### Scenario: Two features own one kind of write
- **WHEN** a second feature gains a write path for a kind of write to a durable port (or key) that
  already has an owner
- **THEN** the design is corrected so one piece of code owns that kind of write

#### Scenario: Two processes running the owning code is not a second owner
- **WHEN** the same owning code writes one durable port from two processes at once, each write one
  guarded transaction
- **THEN** the ownership rule is met; safety against the interleaving is the guard's, not process
  exclusivity's

### Requirement: A trigger flow never outlives its own run

A `flow/` class SHALL NOT declare a `CoroutineScope` parameter or field, and every lambda parameter it
accepts whose return type is `Unit` SHALL be `suspend`. Its entry point SHALL be `suspend` and SHALL
return only when the work it coordinates has finished; concurrency inside a flow SHALL be expressed with
structured concurrency so that fan-out is preserved while the entry point still awaits its children.

A flow's concurrent children SHALL be **isolated** from one another: a child that fails SHALL be logged
and SHALL NOT cancel its siblings, and the entry point SHALL still await every child. Cancellation of the
flow itself SHALL still propagate to every child. A flow SHALL express fan-out only through the one
isolating helper `model/` provides, never through a bare `coroutineScope { launch { … } }`, because a
flow cannot see which of its collaborators the composition backed with something that throws — the
same reason it cannot see which of its `Unit` lambdas were backed by a detached launch.

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

#### Scenario: One child throws

- **WHEN** the foreground flow's upload pump throws while the status refresh, the download reconcile and
  the stored-upload settle are still running
- **THEN** the failure is logged, the siblings run to completion, and the entry point returns once they
  have

#### Scenario: A flow fans out with a bare coroutineScope

- **WHEN** a `flow/` source file launches children inside `coroutineScope { … }` rather than through the
  isolating helper
- **THEN** the flow-zone gate fails, naming the file

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
fake ports); a decision about which platform mechanism may be used — today the one fact whether the
upload extension may be registered (`upload-lifecycle`, "Whether the extension may be registered is
one pure fact") — SHALL be a pure, unit-tested **total** function from the **OS capability facts it
reads** and current runtime state, and the shell SHALL invoke only the shell-supplied adapter thunks
that answer permits, deciding nothing itself. A fact that is fixed by the compilation target SHALL NOT
be re-derived at runtime and SHALL NOT enter that function. Such a function SHALL be re-evaluated
whenever one of its inputs changes, rather than once per process, so a choice that depends on runtime
state does not force the choice out of the function and into scattered guards.

#### Scenario: The harness cannot drift from production
- **WHEN** the world harness and the device binaries compose the core
- **THEN** they execute the same composition function over different port implementations, so a
  wiring difference is impossible rather than undetected

#### Scenario: A new mechanism or a new input state is added
- **WHEN** a new platform mechanism, or a new value of an input to such a function, is introduced
- **THEN** the function fails to compile until every combination is handled, and its cells are
  unit-tested — including that no cell permits a mechanism the running OS cannot invoke

#### Scenario: A target-fixed fact is not an input
- **WHEN** a fact is already determined by which Kotlin target produced the binary
- **THEN** the function does not take it as an input and no runtime read re-derives it

### Requirement: Shells are wiring only
`:app:*` Kotlin SHALL contain zero conditionals (enforced by a complexity gate); shells construct
adapters, supply thunks, and **delegate** entry points: an OS entry point that is a member
of a process's inbound port (see "OS entry points cross an inbound port") SHALL reach the core by Kotlin
delegation, never by a hand-written forwarding body. A shell SHALL NOT bind the status screen's taps to
container intents itself; it passes the callback bundle the shared factory builds (`sync-status-screen`).
Swift SHALL be a transcriber, not a decider:
it forwards raw ObjC-visible inputs whole, constructs no domain values, and branches on nothing;
where the OS offers an ObjC-visible surface, Kotlin owns the trigger directly. Swift decision
keywords SHALL be pinned to an explicit list of irreducible occurrences, each justified by a
Swift-only API and carrying its forcing proof.

#### Scenario: A decision appears in a shell
- **WHEN** an `if`/`when` beyond the pinned forms appears in `:app:*` Kotlin or a decision
  keyword beyond the pinned list appears in the Swift shells
- **THEN** the respective gate fails, and the fix moves the decision into a tested zone

#### Scenario: A shell hand-writes a tap binding

- **WHEN** an `:app:*` call site of the status screen builds its callback bundle field by field
- **THEN** the binding is replaced by a call to the shared factory, because a second copy of the table is
  where an omitted or crossed callback hides

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
violation: a cache whose absent and unreadable values both cost only a recomputation collapses the
two correctly, provided it says so. The violation is a collapse whose stated consequence does not cover every cause it
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

### Requirement: A build-time-only module is contained by compilation, not by a runtime check
A test-only module that links into a shipped-format binary SHALL be contained at compile time: it is linked only under an explicit build property, and a build without that property SHALL contain **no source of that module at all** — not a stub, not a no-op implementation, and not an inert runtime branch. Such a module SHALL still earn its modulehood the ordinary way, by withholding a third-party or platform dependency from every other module by compile error.

Where such a module needs a call site inside a shell, it SHALL contribute that source itself — a source
directory the shell's build script adds only under the property — rather than requiring the shell to carry
a permanently-compiled seam. The shell's own production source SHALL gain no declaration naming the
module, and any visibility widening it requires SHALL be the narrowest that compiles (`internal` before
`public`, so no platform framework header changes).

Where the thing to be contained is reached **through** a shell's own switch rather than by contributing a
call site — so that removing it would leave the shell naming a type that no longer exists — containment
SHALL be achieved by giving it its **own binary target** over its own module, rather than by keeping an
inert branch. A separate target linking neither the shell module nor the live graph makes inertness a
property the binary cannot express, rather than one that a set of no-op members must each preserve
correctly.

Where the contained module's consumer needs a withholding module's `internal` declarations — an
adapter's operating-system seam, which the port-contract device binding records through — that
withholding module's build script MAY add a source directory to its own source set **only under the
same property**. The same all-or-nothing guarantee applies: without the property the directory is not on
the compile path, and with it the directory and the contained module arrive together. The directory
SHALL depend on nothing beyond the contained module, and no declaration SHALL be widened for it — reaching
`internal` from inside the owning module is the reason it lives there.

Where the contained module must **replace** a call path in a shell rather than add a call site — the rig
build's upload extension routing `process()` to a contract run — the shell's build script SHALL select
between two source directories by the property: a production directory holding the one declaration the
path goes through, and a directory the contained module contributes, declaring the same symbol. Exactly one
is on the compile path. The production directory is shell source like the rest; the contributed one SHALL
hold no decision either, delegating any branch to the contained module or to a withholding module's
property-gated directory, so the shell gate's zero-decision rule holds over both.

This is the inverse of `:adapter:generic:fake`, which never links into a shipped framework at all.

A dev/test control surface SHALL NOT rely on **runtime** inertness in a shipped binary. A launch-environment
variable is inert only because a production launch supplies no environment — a property of how the app is
started, not of what it contains — so it is not a containment mechanism. Where such a surface is wanted, it
belongs behind compile-time containment; a build-property-gated tree MAY read an environment variable,
because the file reading it is absent from a production build.

#### Scenario: A production build contains none of the module
- **WHEN** the app is built without the containment property (any CI, TestFlight, or App Store build)
- **THEN** neither the module nor any source it contributes is on the compile path, and the shipped binary
  contains no declaration of it

#### Scenario: The property links the module and its contributed call site together
- **WHEN** the app is built with the containment property set
- **THEN** the module is on the compile path **and** the source directory it contributes is added to the
  shell's source set, so the call site and the module it names arrive together and cannot be half-present

#### Scenario: A runtime-flag containment is rejected
- **WHEN** containment is proposed as a runtime check — a flag, an environment variable, or a no-op
  implementation compiled into every build
- **THEN** it SHALL be rejected for a module of this kind, because a shipped binary would then contain the
  code whose absence is the guarantee

#### Scenario: A surface reached through the shell's own switch gets its own target
- **WHEN** a dev/test composition is selected by a branch in the shell's own mode switch, so that gating its
  source alone would leave the shell naming a missing type
- **THEN** it is given its own binary target over its own module, linking neither the shell module nor the
  live graph, rather than remaining an inert branch in the shipped one

#### Scenario: A withholding module carries a property-gated source directory
- **WHEN** a withholding module's build script adds a source directory only under a containment property
- **THEN** a build without the property compiles none of it, the directory depends on nothing beyond the
  contained module, and no declaration in the withholding module is widened for it

#### Scenario: The module still withholds a dependency
- **WHEN** the module is added to the module set
- **THEN** its withholding argument is recorded here, and the dependency it withholds is unreachable from
  every other module by compile error

#### Scenario: A shell's call path is substituted under the property

- **WHEN** the upload extension is built with `-Psnapsync.rig`
- **THEN** its build compiles the rig-contributed directory in place of the production one, so the entry the
  operating system calls reaches the contract runner, and a build without the property compiles only the
  production directory, whose entry reaches the core exactly as before

#### Scenario: The substituted directory holds a decision

- **WHEN** the contributed directory's declaration branches on the run request itself
- **THEN** the shell gate fails over it, and the branch moves into the contained module or the withholding
  module's property-gated directory

### Requirement: OS entry points cross an inbound port

Each process that the operating system calls into SHALL declare its OS entry surface as one **inbound port** in
`ports/`: an interface the **core implements** and the shell drives, as opposed to the outbound ports the core
calls. The app process's is `PlatformEntries` (foreground, background, an opened URL, a push token, a silent push,
a background task by identifier, handed-back background transfers by channel); the upload extension's is
`ExtensionEntries` (`process(): CycleResult`, terminate). They are separate because the two processes' entry sets
are disjoint.

An inbound port SHALL obey "Ports are the I/O boundary named for the need": its members are named for what the
operating system is telling the app, never for the platform API that delivers it, and its parameters carry
platform-independent values (a string identifier, a raw payload map, a completion to release), never a platform
type. A platform entry whose input is a platform type (a user activity) SHALL NOT be a member; its filter runs in a
tested zone and calls the port.

The implementation SHALL live in `compose/`, beside the shared composition it drives (see "One shared
composition"), and SHALL hold what the shell once held: the entry → flow command transcription, the construction
and holding of each wake's `OsReceipt`, the entry-point logging, and the routing of a background task or transfer
channel to its handler. That routing SHALL be a comparison against identifiers the shell supplies **as data**, so
no platform constant enters `model/`, `ports/` or `feature/`. What the implementation cannot name because it lives
outside `:domain` (the presentation container, the root's token-source adapter) SHALL reach it as in-process
hooks the root supplies when it obtains the implementation; a hook calls back into the process and never reaches
out of it.

A composition root SHALL implement its process's inbound port by **Kotlin delegation** to that implementation.

#### Scenario: A new OS callback is added

- **WHEN** the app starts handling a new operating-system callback
- **THEN** it becomes a member of the process's inbound port, named for the need, implemented in `compose/` and
  covered by that port's contract, and the root gains no forwarding body for it

#### Scenario: A member is named for Apple's API

- **WHEN** an inbound-port member is proposed whose name or parameter type describes an Apple mechanism (a
  `URLSession` identifier, a processing-result raw value)
- **THEN** it is renamed for the need, and the platform's encoding is applied in the shell or an adapter

#### Scenario: The routing needs a platform constant

- **WHEN** the implementation must decide which handler a background task or transfer channel belongs to
- **THEN** it compares against identifiers the root passed in, and the constant stays in the adapter that owns it

### Requirement: Queries cross a lane-gated door

A query SHALL reach the core only through the lane-decorated user-query bundle.
Every read that `:ui:presentation` invokes as a function rather than observes as a state flow — today the
join-details load and the shareable-count query — SHALL be a field of one `UserQueries` bundle declared in
`model/`, built in `compose/`, and decorated there with its lane exactly as `UserCommands` is. Presentation
SHALL receive it by constructor and SHALL NOT receive any other function-typed port-reaching read. A query
SHALL NOT run port I/O on the thread that invoked it.

#### Scenario: A query is invoked from a composable

- **WHEN** a screen asks for the shareable count for a new cutoff
- **THEN** the call hops to the core lane before touching any port, and the main thread performs no
  database or photo-library read

#### Scenario: A new query seam bypasses the bundle

- **WHEN** a function-typed read is added to `StatusContainerHost`'s constructor outside `UserQueries`
- **THEN** the presentation gate fails until it is moved into the bundle

### Requirement: Callbacks are bound at construction

Production source SHALL NOT declare a function-typed `var` (nullable or not) as a callback slot. A
collaborator that must call back SHALL receive the callback as a constructor parameter; where the callee is
built later than the caller, the callback SHALL resolve the callee at invocation time (for example by
reading a `lazy`) rather than be assigned after construction. A callback that can be absent is a
design decision stated by a nullable constructor parameter, never an unassigned slot.

The rule exists because a slot assigned as a side effect of building something else is wired only on the
paths that build that something — and on every other path it is silently `null`.

#### Scenario: The download jobs' staging callback

- **WHEN** the process is relaunched by the OS only to deliver download-session events, and nothing forces
  the download controller
- **THEN** a staged resource still reaches the controller, because the jobs received their callback at
  construction and it resolves the controller when invoked

#### Scenario: A callback slot is declared

- **WHEN** a production class declares `var onX: ((…) -> Unit)? = null`
- **THEN** the callback-slot gate fails, naming the declaration

### Requirement: Function-typed parameters have no defaults in production

A function-typed **constructor parameter** in production source SHALL NOT declare a default value — that
includes a nullable one defaulting to `null`. A missing wire SHALL be a compile error at the construction
site, never an inert `{}`, `{ null }`, `{ true }` or `{ emptySet() }` that ships. A constructor is where a
seam is wired; a class that needs a production binding its tests replace states it in a secondary
constructor rather than as a default. Compose content-slot types (`@Composable` function types) are exempt,
and so are test sources. Parameters of ordinary functions (a log formatter, an optional callback on a
helper) are not seams and are not covered.

#### Scenario: A host forgets a command

- **WHEN** a host builds `UserCommands` and omits `choosePhotos`
- **THEN** the build fails at that construction site rather than shipping an inert button

#### Scenario: A safety gate has no permissive default

- **WHEN** a cycle collaborator such as `mayCreate` or an exclusion reader is not supplied
- **THEN** the build fails, rather than admitting every photo or creating every job by default

### Requirement: Catch sites keep cancellation

Production source SHALL NOT use `runCatching`, `catch (e: Throwable)` or `catch (e: Exception)` directly.
It SHALL use the `model/` helpers that rethrow `CancellationException` and pass every other failure on as
a value; a deliberate `withTimeout` catch of `TimeoutCancellationException` remains legal. The only
exception is the ObjC-boundary helpers (see "ObjC boundaries contain every throw"), which must catch
everything because nothing may escape into ObjC.

A caught cancellation is not a failure: it SHALL NOT be reported as one, logged at `Error`, or converted
into a domain failure outcome.

#### Scenario: A cancelled join

- **WHEN** a join's HTTP call is cancelled because its caller was cancelled
- **THEN** the cancellation propagates, and no `JoinResult.FAILED` is produced for it

#### Scenario: A best-effort step is cancelled

- **WHEN** a leave's best-effort step is cancelled
- **THEN** the cancellation propagates, and no Error-severity line (and so no crash-report event) is
  emitted for it

### Requirement: A multi-step use case declares which steps are required

A use case that runs a sequence of steps SHALL declare each step either **required** or **best-effort**. A
failed required step SHALL stop the sequence and SHALL be returned to the caller as a failure outcome; a
failed best-effort step SHALL be logged and the sequence SHALL continue. A step whose failure would leave a
later step acting on state that was never persisted SHALL be required.

#### Scenario: The reconfigure save fails

- **WHEN** the reconfigure's config save throws
- **THEN** no later step runs, and the command returns a failure outcome

#### Scenario: A best-effort step fails

- **WHEN** the reconfigure's album gather fails after the save landed
- **THEN** the failure is logged and the download arm is still re-driven

### Requirement: ObjC boundaries contain every throw

No Kotlin exception SHALL escape into Objective-C, and no Objective-C failure result SHALL be discarded.
Every Kotlin lambda handed to an Objective-C API as a block, and every Kotlin override of an Objective-C
delegate method, SHALL run its body through the boundary helper, which catches every throwable, logs it at
`Error`, and returns normally — because an exception that escapes into Objective-C terminates the process.
Every call to an Objective-C API that reports failure through a `Boolean` or `NSError**` result SHALL go
through the checked-call helper, which reads that result and reports a failure rather than discarding it.

#### Scenario: A store write fails inside a PhotoKit change block

- **WHEN** a SQLite write inside a `performChanges` block throws because the disk is full
- **THEN** the import is recorded as failed and the process keeps running

#### Scenario: A background task submit is refused

- **WHEN** `BGTaskScheduler.submitTaskRequest` returns `false` with an `NSError`
- **THEN** the refusal and the platform's error value reach the device log

### Requirement: State reached from OS callbacks is confined

Mutable state that an adapter or feature touches from an OS callback thread SHALL be confined to one
named serial lane: it is read and written only on that lane, or held in a thread-safe primitive whose
operations are atomic for the use made of them. A source that emits a sequence of snapshots derived from
OS change notifications SHALL emit them from one serial lane, in the order of the changes they reflect,
and SHALL emit nothing after observation has ended.

#### Scenario: Two quick selection changes

- **WHEN** the member changes the limited selection twice in quick succession
- **THEN** the snapshot reflecting the second change is the last one emitted

#### Scenario: A download is staged while the handler drains

- **WHEN** a staged resource's import is registered on the delegate queue while the background-events
  receipt drains the outstanding imports on the scope
- **THEN** the drain either awaits that import or the import is registered after the drain, and it is
  never lost between the two

### Requirement: Reads that can be unknown say so

A read whose source can be unreadable (a membership file before first unlock, a protected store) SHALL
return a sealed result that distinguishes the known answer from **unreadable**, and SHALL NOT fold
unreadable into one of the known answers (`null` meaning "not a member", `false` meaning "not joined").
A caller that returns early on unreadable SHALL log it with its consequence. A predicate SHALL be named for
the set of states it accepts: a permission predicate true under both full and limited access is named for
usable access, not for a grant.

#### Scenario: The membership is unreadable at an upload transition

- **WHEN** an upload transition reads the membership before first unlock
- **THEN** it receives `Unreadable`, logs that it deferred and why, and does not treat the device as
  having left

#### Scenario: A predicate's name matches its binding

- **WHEN** a collaborator is bound to "full or limited access"
- **THEN** its name says usable access, and no caller compares it against a full grant

