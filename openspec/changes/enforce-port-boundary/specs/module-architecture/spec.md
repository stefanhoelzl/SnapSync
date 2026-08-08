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
