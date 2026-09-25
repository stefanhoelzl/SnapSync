## MODIFIED Requirements

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

The process-wide **opportunistic tail** (capability `ios-app-shell`, "Each OS wake does its own work, then
hands the rest to one opportunistic tail") does not weaken this law, because it is **not a flow's child**. A
flow orders a wake's **own work**, and its entry point returns only when that own work has finished — which is
exactly what the handler's release waits for. An OS wake's hand-off to the tail SHALL be made by the inbound
port's implementation in `compose/` **after** the flow has returned, never from inside a flow: a flow that
requested the tail would be handing work to something that outlives it, which is the detachment this law
forbids. That includes the decision **whether** a wake joins the tail — a silent push's active-event guard
(capability `push-registration`) is asked by the inbound port's implementation after the `SilentPush` flow
returns, not by the flow, whose entry point answers nothing.

One request reaches the tail from a flow's call path, and it is not the flow's: a **membership transition's
arm** (capability `upload-lifecycle`), which a join reaches from inside the `Provision` flow, requests the tail
**detached** through the uploader seam the composition builds. The flow neither holds the runner nor awaits the
tail; the join's own work is complete when the flow returns, no OS handler waits on it (a join is a tap), and
the tail the arm starts is the process's — joined, if one is running, like any other request. A `flow/` class
SHALL NOT itself take the tail runner or a tail request as a collaborator.

The tail's lifetime is bounded by the process's background time and the operating system's expiry signal, not by
any flow's run. Decision record: `changes/own-work-per-wake` (D1, D5).

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

- **WHEN** one of the foreground flow's concurrent children throws while its sibling children are still
  running
- **THEN** the failure is logged, the siblings run to completion, and the entry point returns once they
  have

#### Scenario: A flow hands work to the tail

- **WHEN** a `flow/` class gains the tail runner, or a lambda that requests the tail, as a collaborator
- **THEN** the law is violated — an OS wake's tail is requested by the inbound port's implementation after the
  flow returns, so the flow's entry point still returns only when the work it coordinates has finished

#### Scenario: A push's tail decision is taken after its flow

- **WHEN** a silent push's own work has run
- **THEN** the inbound port's implementation, not the `SilentPush` flow, asks whether the pushed event is the
  active one and requests the tail only then

#### Scenario: A flow fans out with a bare coroutineScope

- **WHEN** a `flow/` source file launches children inside `coroutineScope { … }` rather than through the
  isolating helper
- **THEN** the flow-zone gate fails, naming the file


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
  Its thread SHALL be pinned to `QOS_CLASS_USER_INITIATED` by the lane's first task, before any other
  work can run on it, and the pin SHALL be logged once with the class it replaced.

The pin is forced by a measurement, not a preference: the blocking platform calls this lane carries
(PhotoKit commits and fetches, the Keychain, SQLite on the App Group) are XPC round-trips, and the calling
thread's QoS **propagates** over them — measured on an SE2, PhotoKit commits issued at
`QOS_CLASS_BACKGROUND` took ~400 ms against ~60 ms at a foreground class (6–7×). The pin decides only the
class requested *within* the process; it does not lift the kernel's `darwinbg` clamp on a process the
operating system runs in the background, and SHALL NOT be described as doing so. Its energy cost in the
background is unmeasured and accepted.

One **adapter-owned hop lane** SHALL exist beside the three: the **PhotoKit read lane** — a single thread,
pinned to `QOS_CLASS_USER_INITIATED` the same way, owned by `:adapter:ios:ext-safe` and used by the PhotoKit
reads (the discovery walk and the candidate source) in whichever process links them. Its meaning is
**throughput**, as for any adapter hop below: a synchronous `assetsd` round-trip does not hold the serial
composition lane. It is one thread deliberately — each caller's reads are already sequential, so two
callers' reads queue rather than overlap (PhotoKit's daemon is where they would meet anyway), and a wedged
`assetsd` parks that thread rather than a worker of the pool presentation-state reduction runs on. It SHALL
be a dedicated thread rather than a pin applied inside a shared pool's worker, because a pooled thread left
at `USER_INITIATED` would carry that class into unrelated work and `UNSPECIFIED` cannot be restored.
Decision record: `changes/own-work-per-wake` (D13, the stage-1 sync).

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

#### Scenario: A PhotoKit read runs at a foreground class

- **WHEN** the discovery walk or the candidate source issues a PhotoKit read, in the app or the extension
- **THEN** it runs on the PhotoKit read lane's single thread at `QOS_CLASS_USER_INITIATED`, off the
  composition lane, whatever class the calling process was launched at

#### Scenario: The composition lane's class is recorded

- **WHEN** the live composition lane starts
- **THEN** its first task pins the thread to `QOS_CLASS_USER_INITIATED` and logs the class it replaced, and
  no other work has run on that thread before it

#### Scenario: A second platform inherits the lane reasoning
- **WHEN** a platform is added whose background work runs in the app's own process
- **THEN** the three-lane arrangement is re-derived against that platform's facts rather than
  carried over, because the measurement and the two-process argument behind it are iOS-shaped


### Requirement: OS entry points cross an inbound port

Each process that the operating system calls into SHALL declare its OS entry surface as one **inbound port** in
`ports/`: an interface the **core implements** and the shell drives, as opposed to the outbound ports the core
calls. The app process's is `PlatformEntries` (foreground, background, an opened URL, a push token, a silent push,
a background task by identifier, that background task's expiry by the same identifier, handed-back background
transfers by channel); the upload extension's is
`ExtensionEntries` (`process(): CycleResult`, terminate). They are separate because the two processes' entry sets
are disjoint.

An inbound port SHALL obey "Ports are the I/O boundary named for the need": its members are named for what the
operating system is telling the app, never for the platform API that delivers it, and its parameters carry
platform-independent values (a string identifier, a raw payload map, a completion to release), never a platform
type. A platform entry whose input is a platform type (a user activity) SHALL NOT be a member; its filter runs in a
tested zone and calls the port.

Where the operating system offers an **expiry signal** for an entry — a `BGTask`'s `expirationHandler` — the
inbound port SHALL carry it into the core, shaped for the need ("this background task's time is up") and keyed
by the identifier the operating system delivered, so the core that holds the task's completion is the one that
stops the work and releases it — at once, without waiting for the unit in flight (capability `ios-app-shell`,
"Expiry stops work cooperatively at the next boundary"). The shell SHALL forward the signal and decide nothing, and SHALL NOT answer it
itself (capability `ios-app-shell`). Where the operating system offers no expiry signal on the entry itself — a
silent push, a background-`URLSession` wake — the core SHALL obtain one through the outbound background-time port
(see "Background time is an outbound port named for the need"), not through the inbound port. The upload
extension's inbound port SHALL carry no expiry signal, because the extension has none: measured, its only end is
a hard kill (capability `ios-photokit-upload`). Decision record: `changes/own-work-per-wake` (D3, D8).

The implementation SHALL live in `compose/`, beside the shared composition it drives (see "One shared
composition"), and SHALL hold what the shell once held: the entry → flow command transcription, the holding of
each wake's OS completion handler across that wake's own work, the hand-off of the rest to the opportunistic tail,
the forwarding of the operating system's expiry signals into the running work, the entry-point logging, and the
routing of a background task (and its expiry) or transfer channel to its handler. It also holds what the shell
never held: the process's background time for each push, transfer and foreground wake (see "Background time is
an outbound port named for the need"), and whether a wake's own work is followed by a tail at all. That routing SHALL be a comparison against identifiers the shell supplies **as data**, so
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

#### Scenario: A background task's expiry reaches the core

- **WHEN** the operating system fires a `BGTask`'s expiration handler
- **THEN** the shell forwards it through the inbound port (`onBackgroundTaskTimeUp`) with the delivered
  identifier, and the port's implementation in `compose/` requests the running tail's stop and releases the
  completion it holds at once — the shell completes nothing itself

#### Scenario: The routing needs a platform constant

- **WHEN** the implementation must decide which handler a background task or transfer channel belongs to
- **THEN** it compares against identifiers the root passed in, and the constant stays in the adapter that owns it


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

- **WHEN** a delivered resource's staging is registered on the delegate queue while the background-events
  handler's drain awaits the outstanding staging on the scope
- **THEN** the drain either awaits that staging or the staging is registered after the drain, and it is
  never lost between the two — the photo-library import that follows it is the opportunistic tail's, and
  finds the staged bytes in the store either way


## ADDED Requirements

### Requirement: Background time is an outbound port named for the need

The app process's background time SHALL be reached through one outbound port in `ports/`, named for the need —
"keep this process running while I finish, and tell me when time is up" — never for the platform API that serves
it. Its surface SHALL be platform-free: beginning a hold takes a label for the diagnostic line and an expiry
callback, and returns a handle whose only operation is ending that hold. It SHALL carry no duration, no
remaining-time read and no estimate, because the operating system's signal is the only notion of "time is up" the
app acts on (capability `ios-app-shell`, "Time is up is learned only from the operating system"). It SHALL be a
required `AppPorts` field (`backgroundTime`), with no default: a composition without it would hold nothing, and no
expiry would ever stop a tail.

The expiry callback SHALL be invoked **at most once**, on a thread the port does not choose (the main thread on
iOS), and possibly **before the begin call returns**: a process whose time is already up is refused a hold, and
that refusal SHALL be reported as an immediate expiry rather than as an error, because it means the same thing to
the caller. An expiry SHALL NOT end the hold by itself — the core ends it, and it does so **at once, from inside
its expiry callback**, after requesting the tail's stop and releasing any OS handler the wake still holds (capability
`ios-app-shell`, "Expiry stops work cooperatively at the next boundary"). Ending SHALL be idempotent: the first call
ends the hold with the operating system and every later call does nothing, so a core that ends on more than one path
can neither end a hold twice nor end another hold that reuses the platform's identifier. A hold that is never ended
is, per Apple, a termination.

The iOS adapter SHALL be `UIApplication.beginBackgroundTask(withName:expirationHandler:)` /
`endBackgroundTask(_:)` — a refusal is `UIBackgroundTaskInvalid` — and SHALL live in `:adapter:ios:app-only`:
`UIApplication` is unavailable to app extensions, and the upload extension has no such signal to offer (capability
`ios-photokit-upload`), so this port SHALL NOT be linked into, bound in, or faked for the extension's composition.
The honest in-memory double in `:adapter:generic:fake` SHALL let the world harness and tests fire the expiry. The
port SHALL be covered by a contract both bindings extend, whose clauses are the ones a real host can present: a hold
is granted while time remains, holds do not refuse each other, and ending is safe to repeat. The contract carries
**no** expiry clause — no host lets a binding enter "time is up", and a clause only the double could reach may not
exist (capability `port-contracts`) — so the adapter's expiry behaviour is tested over its own operating-system seam,
and the core's reaction to an expiry over the double.

Because background time is **per app**, not additive per hold, a hold taken while another is active costs no time;
the core MAY therefore take one for each wake without accounting between them.

Decision record: `changes/own-work-per-wake` (D3, D4, D5).

#### Scenario: A push wake takes background time for its tail

- **WHEN** a silent push is handed over
- **THEN** the core begins a hold through the background-time port no later than the handover, and ends it when
  the tail it handed to has finished, or at once on the hold's expiry

#### Scenario: The operating system says time is up

- **WHEN** the operating system fires the background task's expiration handler
- **THEN** the adapter invokes the core's expiry callback and returns; inside it the core requests the tail's
  stop, releases the handler it guards and ends the hold — exactly once — without waiting for the unit in flight

#### Scenario: A refused hold is an immediate expiry

- **WHEN** the operating system refuses a background task because the app's time is already up
- **THEN** the port reports it as an expiry before the begin call returns, and the wake that asked requests no
  tail

#### Scenario: The extension cannot reach the port

- **WHEN** the extension's composition is built
- **THEN** it binds no background-time adapter, and `:adapter:ios:ext-safe` names no `UIApplication` API
