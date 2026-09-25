## MODIFIED Requirements

### Requirement: The module set withholds; packages organize
The system SHALL consist of exactly the modules enumerated below, and **every** module the build
declares SHALL appear in exactly one group. A group names the law that justifies its members'
existence; a module justified by no law is a package with a derived text gate instead.

- **Withholding modules** — each exists because it withholds a dependency from its consumers by compile
  error. The withheld dependency is usually a third-party or platform one. It MAY also be **another zone
  of the core**, where a module boundary is the only construction that makes the zone edge unresolvable
  rather than merely forbidden. Members:
  - `:domain:model`, `:domain:ports`, `:domain:feature`, `:domain:flow`, `:domain:presentation`,
    `:domain:compose`, `:domain:host` — the core's six zones and the host ("Zones inside the core"), each
    depending only along the permitted zone edge and only via `implementation()`, so no zone leaks
    transitively; no `iosMain` source directory anywhere in the tree. The host is the shared host
    composition ("One shared composition"): the one module that sees both the composition zone and
    presentation, so that neither gains the other — presentation never resolves the composition zone, and
    the composition zone never resolves presentation;
  - `:ui:screens`, `:ui:components` (the only module that may depend on Material 3);
  - `:adapter:ios:ext-safe`, `:adapter:ios:app-only`, `:adapter:generic:app`, `:adapter:generic:fake`;
  - `:app:ios`, `:app:ios:extension`, `:app:desktop`;
- **Contained modules** — each exists so that something is absent from a production build, governed by
  "A build-time-only module is contained by compilation, not by a runtime check":
  - `:app:ios:forge` — its own binary target, linked under `-Psnapsync.forge`.
  - `:test:rig` — contributes its own call site into the iOS app shell, linked under `-Psnapsync.rig`. Its
    JVM target, the control channel's JVM host, links into no shipped-format binary and is consumed only by
    test equipment.
  - `:test:contracts` — the port contracts, linked under `-Psnapsync.rig` into the app, into the upload
    extension, and into the rig-gated source sets of the two iOS adapter modules, the extension-safe one and the
    app-only one. It
    withholds the test-assertion library from every other main source set: it is the only module whose main
    code may assert.

  A contained module is grouped by the law that governs it, **not** by its name prefix: these three are the
  same species and the containment law describes exactly their shapes.
- **Support modules** — never linked into any shipped-format binary, and exempt from the production-module
  laws: `:test:world`, `:test:integration`, `:test:architecture`, `:test:harness-driver`, `:tools:diagrams`,
  `:test:edge` (the real backend served as a local process for JVM tests, JVM-only), `:test:control` (the
  typed client of the control channel's protocol, JVM-only).
  - The client depends on the control channel's JVM variant for the wire types. Those stay in the contained
    channel module, because the device build needs them without a client.
  - The channel declares its own module dependencies with `implementation()`. So the client and the
    integration surface built on it compile against `model/`, presentation and `feature/`, each declared
    explicitly rather than received transitively, and never against `ports/`, `flow/`, `compose/`, the host
    or the world. That compile boundary, together with the read-model import gate (capability
    `architecture-guards`, "The zone gates"), which confines the client's `feature/` references to read-model
    packages, is the whole of the client's read-model rule.

**The core's zone split** is the one place the withholding law is satisfied by an **internal** boundary, and it
is admitted for a stated reason: the zone edges were previously held by text gates that had to enumerate
the forms a violation could take, could not see generated source, and passed green when their scope
directory was renamed. A module boundary enumerates nothing and cannot be renamed into passivity. The cost
is bounded and was measured before the split: eighteen `internal` declarations across the whole core, none
in `ports/`, `flow/` or `compose/`.

**The adapter tree** SHALL be uniformly two-level — `adapter:<platform-axis>:<linkage-leaf>` — with each
platform-axis prefix (`adapter/ios/`, `adapter/generic/`) a pure path grouping that is not itself a
module (no build file: a prefix module would withhold nothing). The core's `domain/` prefix is likewise a
path grouping and not itself a module. All finer structure SHALL be packages
whose boundaries are enforced by derived text gates, not modules. The named test-equipment zone
(harness panels, world inspector, the remote mirror) is likewise exempt from production-module laws.

**The enumeration** SHALL be exhaustive and SHALL NOT use wildcards: it is the expected value the
module-set gate compares the build's include set against (capability `architecture-guards`), and a
wildcard cannot be compared. Within a group, a backticked `:`-prefixed token **is** a membership
claim; prose in a group SHALL refer to another module by description rather than by its backticked
path, or it silently enrols that module in a second group. Adding a module therefore requires amending this
requirement with the group it joins and the argument for that group.

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

- **WHEN** code in the control channel's client module, the integration surface or their tests names a type
  from `ports/`, `flow/`, `compose/` or the world
- **THEN** compilation fails (unresolvable symbol), because no dependency on its compile path exports those
  zones

#### Scenario: The contracts module in the upload extension

- **WHEN** the upload extension is built with `-Psnapsync.rig`
- **THEN** the contracts module is linked into it together with the rig-gated source it runs through, and a
  build without the property contains neither

#### Scenario: Presentation reaches for the composition

- **WHEN** code in the presentation zone references the core's composition zone, or the composition zone
  references presentation
- **THEN** compilation fails, because only the host depends on both

### Requirement: Zones inside the core
The core SHALL consist of exactly six zones and the host, each its own module under `domain/` with its
package at `app.snapsync.<zone>` (the host's is `app.snapsync.host`), and each depending on the others only
along these edges, through `implementation()`:

- `model/` — vocabulary, domain services, pure codecs, `UiState`, and **every pure-data type a port
  carries**. It imports nothing project-internal.
- `ports/` — every port interface, plus the port-adjacent logic that a later change re-homes. It imports
  `model/` only.
- `feature/` — imports `model/` and `ports/` only; no feature references a sibling feature. A feature type
  that code outside `feature/` consumes SHALL be declared in that feature's `readmodel` package
  (`feature/<feature>/readmodel/`). That package is the mechanical definition of a read-model.
- `flow/` — imports `model/` and `feature/` only; never `ports/`.
- `presentation/` — the UI-state reduction. It imports `model/` and, from `feature/`, read-model packages
  only.
- `compose/` — may import every zone except `presentation/` and the host; holds the shared composition, flow
  decorators, and port-state-transition subscriptions.
- the host (`host/`) — the shared host composition. It imports `model/`, `ports/`, `compose/`,
  `presentation/` and, from `feature/`, read-model packages only.

A reference across an edge not listed here SHALL NOT resolve. The two rules that a module edge cannot express
— sibling features, and read-model packages within `feature/` — SHALL be held by derived text gates
(capability `architecture-guards`, "The zone gates").

A **pure-data type** is a data class, an enum class, or a sealed class or interface that references no port
and carries no logic beyond its own members. A port's pure-data types SHALL be declared in `model/`. A type
that fails the test (an interface, a class that wraps a port, a function over port calls) SHALL stay in
`ports/`.

**Allowed targets.** The core's modules and the `:ui:*` modules SHALL compile for exactly the allowed targets
— `jvm`, `iosArm64` and `iosSimulatorArm64` — declared once, by one targets convention plugin in the
`build-logic/` included build, which every one of those modules applies. No such module SHALL declare its own
target list. It MAY configure a target the plugin declared, for example its test runtime. Adding a target is
an edit to the plugin and to this list, not a change of any other law.

#### Scenario: A feature references a sibling feature
- **WHEN** any file under `feature/<a>/` contains a reference (imported or fully qualified) to a
  declaration under `feature/<b>/`
- **THEN** the feature-blindness gate fails, naming both packages

#### Scenario: A flow reaches a port
- **WHEN** any file under `flow/` contains a reference to a declaration under `ports/`
- **THEN** the reference does not resolve and compilation fails, because the flow zone's module does not
  depend on the ports zone

#### Scenario: Presentation reaches past a read-model
- **WHEN** a file in the presentation or host zone references a `feature/` declaration outside a `readmodel`
  package
- **THEN** the read-model import gate fails, naming the file and the reference

#### Scenario: A port gains a result type
- **WHEN** a new sealed result type is introduced for a port's method, referencing no port and carrying no
  logic
- **THEN** it is declared in `model/`, and the port in `ports/` imports it

#### Scenario: A module declares its own targets
- **WHEN** a core or `:ui:*` module lists a Kotlin target that the targets plugin did not declare
- **THEN** it is corrected to apply the plugin and configure only the targets the plugin declares

### Requirement: One shared composition
Every binary that assembles the live core SHALL call the shared composition, and there SHALL be no second
wiring:
- `snapSyncHost`, declared in the host zone (`:domain:host`), for the app — the core from `snapSyncApp`,
  **and** the status host and the host-assembly subscriptions;
- `uploadCore` for the extension's strict subset bundle.

**Inputs.** The composition functions SHALL receive a `CoroutineScope`.
- `snapSyncHost` SHALL receive the app's ports and nothing else.
- The credential-carrying HTTP client SHALL report the backend's verdicts through the one object the core
  exposes for them, never through callbacks a root assembles one by one.
- A root SHALL NOT construct the status host, install a subscription, or pass a read-model to the host itself.

**Testing.** The wiring graph SHALL NOT be unit-tested. It is smoke-tested end to end by the world harness and
by the integration surface over fake ports.

**Platform-mechanism decisions.** A decision about which platform mechanism may be used — today the one fact
whether the upload extension may be registered (`upload-lifecycle`, "Whether the extension may be registered
is one pure fact") — SHALL be a pure, unit-tested **total** function from the **OS capability facts it reads**
and current runtime state. The shell SHALL invoke only the shell-supplied adapter thunks that answer permits,
deciding nothing itself.
- A fact that is fixed by the compilation target SHALL NOT be re-derived at runtime and SHALL NOT enter that
  function.
- Such a function SHALL be re-evaluated whenever one of its inputs changes, rather than once per process, so
  a choice that depends on runtime state does not force the choice out of the function and into scattered
  guards.

#### Scenario: The harness cannot drift from production
- **WHEN** the world harness, the control channel's JVM host and the device binaries compose the core and the
  status host
- **THEN** they execute the same composition function over different port implementations, so a
  wiring difference is impossible rather than undetected

#### Scenario: A read-model the host observes is added
- **WHEN** a new read-model joins the status host's sources
- **THEN** it is wired once, inside the shared host composition, and every root's host observes it without
  a root being edited

#### Scenario: A new mechanism or a new input state is added
- **WHEN** a new platform mechanism, or a new value of an input to such a function, is introduced
- **THEN** the function fails to compile until every combination is handled, and its cells are
  unit-tested — including that no cell permits a mechanism the running OS cannot invoke

#### Scenario: A target-fixed fact is not an input
- **WHEN** a fact is already determined by which Kotlin target produced the binary
- **THEN** the function does not take it as an input and no runtime read re-derives it

### Requirement: Commands cross one door
Every command SHALL cross `flow/`: user taps, OS callbacks, and port-state transitions are the
three driver kinds. `flow/` SHALL define command types; instances SHALL be built, decorated, and
(under a forge directive) substituted only in `compose/`; the presentation zone SHALL receive the
command bundle by constructor and SHALL NOT reference flow callables directly. Reads SHALL NOT
cross flow: features expose read-model projections (state flows) that presentation observes
directly; presentation SHALL NOT invoke feature commands. Adapter outbound callbacks SHALL be
declared on the port and satisfied only by compose-built lambdas whose body is a single flow
command call. Port-state-transition subscriptions SHALL be installed in `compose/` with their
transition semantics tested in a feature. Flows are commands, not sessions: multi-step
interactions are presentation-owned choreography of one-shot commands, and interaction state
dies with the UI. The trigger inventory SHALL be derived from entry points, never hand-enumerated.

#### Scenario: The UI bypasses the door
- **WHEN** the presentation zone references a feature command (suspend function) or a flow callable
  directly
- **THEN** the build fails: a flow callable does not resolve, because presentation has no edge to
  `flow/`, and a feature command lies outside every `readmodel` package, so the read-model import
  gate fails. Only feature read-model types and the injected command bundle are legal

#### Scenario: An OS event bypasses the door
- **WHEN** an adapter's outbound callback is wired to call a feature directly
- **THEN** the wiring is corrected so the compose-built lambda calls a flow command

### Requirement: Ports are the I/O boundary named for the need
The system SHALL access anything touching an external system (time, timezone, files, network,
environment, and platform facilities included) only through a port interface declared in `ports/`,
named for the need it serves (the name must remain correct if a second platform ships), never
for the technology satisfying it. The pure-data types a port carries are declared in `model/`
("Zones inside the core"), so an adapter and a feature share them without either naming the
other's zone. Adapter modules SHALL hold implementations only, named for the
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
- **WHEN** a new technology library is used anywhere in `:domain`, the presentation zone included
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

### Requirement: Queries cross a lane-gated door

A query SHALL reach the core only through the lane-decorated user-query bundle.
Every read that the presentation zone invokes as a function rather than observes as a state flow — today the
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
