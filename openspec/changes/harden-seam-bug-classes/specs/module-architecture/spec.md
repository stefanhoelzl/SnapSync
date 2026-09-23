## MODIFIED Requirements

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

## ADDED Requirements

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

A function-typed parameter or field in production source SHALL NOT declare a default value. A missing
wire SHALL be a compile error at the construction site, never an inert `{}`, `{ null }`, `{ true }` or
`{ emptySet() }` that ships. Compose content-slot parameters (`@Composable` function types) are exempt, and
so are test sources.

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
