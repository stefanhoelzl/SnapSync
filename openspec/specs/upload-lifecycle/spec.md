# upload-lifecycle Specification

## Purpose

The **tier-neutral upload arm**: which producer verb fires on which membership transition (provision,
event switch, permission grant, direction change, leave), and the invariant that **no transition ever
destroys durable dedup state**. Each upload tier supplies the mechanism behind a two-verb `UploadProducer`
seam (`start` / `stop`); this capability owns the decision, and it owns it in one tested, platform-free
place.

It exists because the upload lifecycle previously had **no owner**. It was smeared across the two tier
specs and the iOS composition root — a file in `:app:ios`, which the project's own hard rule declares
wiring-only and untested (capability `testing-architecture`) — so no contract described it and no test could reach it. When a second upload tier arrived, the
app-driven tier (iOS 18–26.0) inherited a PhotoKit-shaped "disable→enable" re-registration ritual on every
provision. Its *disable* half resolved to a full leave (cancelling transfers and the `BGProcessingTask`
heartbeat, wiping the ledger **and** the discovery cursor) while its *enable* half was a no-op below iOS
26.1. Joining an event therefore tore the upload arm down, started nothing, and re-uploaded the user's
whole post-cutoff library on the next cycle — on the tier every current user runs.

The two-verb seam is the fix, and it is a **structural** one. With no destructive verb to reach, there is
no edge from *provision* to *destruction* to get wrong: the bug is unrepresentable rather than merely
absent. Durable state is device-global dedup (`sync-ledger`, "Event-independent key") and stays true across
a leave, a switch, and a re-join; only a triggered reconciliation's `resetTo` ever re-baselines it
(`event-rejoin-reconciliation`). Selecting exactly one producer per process likewise makes the two tiers'
mutual exclusion structural — the non-selected tier's mechanism is never constructed, so it cannot run and
cannot become a second `LedgerWriter`.

The transition table is written against the **current single-active-membership contract** (capability
`join-event`): *provision* and *switch* assume one configured event, and no membership means no arm.
Concurrent multi-event membership is a named future direction; the durable pieces already compose with
it (the ledger key is event-independent, bytes are device-partitioned), so that future reworks the
arm's decision table, not the dedup state — and until then, new work SHALL NOT deepen the
single-membership assumption beyond what this table already encodes.

Decision record: `changes/archive/2026-07-12-fix-app-driven-upload-lifecycle`.

That settling with the platform is owed on every cycle reaching a usable membership, and that a
cycle's publication is decided by its outcome rather than by where it returned, were added in
`changes/archive/2026-08-27-fix-cap-truncation-loop`.

## Requirements

### Requirement: Upload producer seam has no destructive verb

The system SHALL express the upload arm's **lifecycle** as a platform-free `UploadProducer` seam in `:domain`'s
`feature/upload` zone (package `app.snapsync.feature.upload`) with exactly **two** verbs:

- `start()` — begin or resume uploading for the currently-configured membership.
- `stop()` — cease uploading. It SHALL NOT destroy **dedup state**: it SHALL NOT clear the ledger and
  SHALL NOT delete stored bytes.

There SHALL be **no** destructive verb on the seam. No lifecycle transition — provision, re-provision,
event switch, permission change, direction change, or leave — SHALL clear the ledger. Durable dedup state
is device-global (`sync-ledger`), and divergence from storage is repaired by reconciliation
(`event-rejoin-reconciliation`), never by a lifecycle wipe.

The trigger surface ("Triggers are delivered to the mechanism and declined explicitly") SHALL be a
**separate** seam on the same object, so this lifecycle seam keeps exactly the two verbs above and the
orchestrator is given no trigger to invoke.

The property being defended is **dedup**: the proof that a photo is already in the event. Destroying it
re-uploads a member's whole post-cutoff library — the failure this project exists to prevent. The ledger's
`COMPLETED` rows and the stored bytes are that proof; the **discovery cursor is not**. A cleared cursor
costs one full re-enumeration, which finds nothing new, because dedup lives in the ledger it did not touch.

A tier's `stop()` MAY therefore clear its **discovery cursor**, but **only** as a repair for damage its own
mechanism causes, **only** where `COMPLETED` rows survive so nothing already stored re-uploads, and
**only** where the mechanism that runs next cannot repair that damage itself. The conditions are the rule,
not the tier: clearing a cursor for tidiness, where dedup state would not survive it, or where the incoming
mechanism reconciles precisely, remains forbidden. (The PhotoKit tier is the standing instance — the OS's
extension-disable wipes every in-flight job, and `clearRequested()` alone leaves the cleared photos
un-rediscoverable behind a settled cursor. That repair is required by `ios-photokit-upload`, which owns it,
and is scoped there to the re-register.)

Each tier SHALL supply one `UploadProducer` implementation binding these verbs to its own mechanism.

#### Scenario: The seam exposes no way to destroy dedup state

- **WHEN** the `UploadProducer` seam is inspected
- **THEN** it exposes only `start()` and `stop()`, and no lifecycle caller can clear the ledger through it

#### Scenario: Stopping preserves dedup state

- **WHEN** `stop()` is called on either tier
- **THEN** in-flight uploads cease, but every ledger row and every stored object is left intact

#### Scenario: A tier may clear its own cursor to repair its own mechanism

- **WHEN** a tier's `stop()` clears its discovery cursor as a repair for jobs its own mechanism wiped, its `COMPLETED` ledger rows survive, and the mechanism that runs next cannot repair those jobs itself
- **THEN** that is permitted: the next cycle re-enumerates fully and creates no upload job for anything already stored

#### Scenario: Clearing a cursor that dedup depends on is still forbidden

- **WHEN** a tier's `stop()` would clear a cursor without its `COMPLETED` rows surviving, or for any reason other than repairing damage no incoming mechanism can repair
- **THEN** that is forbidden — the carve-out is conditioned on dedup surviving and on the repair being needed, not on which tier is asking

#### Scenario: The repair does not fire when the incoming mechanism reconciles precisely

- **WHEN** a `stop()` is part of a switch to a mechanism that reconciles stranded in-flight rows precisely from its own enumeration
- **THEN** the blanket clear and the cursor reset do not run, and the incoming mechanism's own reconciliation recovers the stranded rows

### Requirement: Lifecycle orchestration is tier-neutral and tested

The decision of **which verb fires on which transition** SHALL live in a tier-neutral orchestrator in
`:domain`'s `feature/upload` zone, not in the app composition root, and SHALL be tested in `commonTest`
(running on both JVM and `iosSimulatorArm64`) against fake `UploadProducer`s, so it is exercised on JVM
**and** `iosSimulatorArm64` rather than only inside an iOS process. The orchestrator SHALL translate
membership and permission transitions into `start()`/`stop()` and nothing else: it holds no ledger, no
cursor, and no storage handle, so a lifecycle transition **cannot** destroy dedup state — the seam gives
it no verb that could.

Photo access is **usable** when it is `GRANTED` or `LIMITED`. A producer SHALL be started only when an
event is configured **and** photo access is usable **and** the membership's direction includes upload.
The orchestrator SHALL start the producer that **resolution** yielded for the current OS facts,
permission, and override ("The upload mechanism is resolved, never selected") — which on an OS carrying
more than one mechanism is the OS-driven one under `GRANTED` and the app-driven one under `LIMITED` (the
OS never invokes the extension under a partial grant; capability `ios-photokit-upload`) — and SHALL hold
the exactly-one-started invariant (see "Exactly one producer started per process"). It SHALL NOT choose
among composed producers: it holds one reference, and a change of resolved kind is what makes it swap.
The orchestrator SHALL
bind the transitions as follows, where the upload arm is enabled exactly when photo access is usable
**and** the configured membership's direction includes upload (`join-event`):

| Transition | Action |
| --- | --- |
| provision / re-provision, arm enabled | `start()` on the permission-selected producer |
| provision / re-provision, access usable but direction is download-only | `stop()` |
| provision / re-provision, access not usable | neither (the grant transition will drive it) |
| transition to usable access (`GRANTED` or `LIMITED`), arm enabled | `start()` on the permission-selected producer |
| transition between usable states (`GRANTED` ↔ `LIMITED`), arm enabled | re-resolve; if the kind changed, `stop()` the outgoing producer, then `start()` the incoming one |
| transition to usable access, **no event configured** | neither |
| leave | `stop()` |

Leave SHALL be `stop()` plus clearing the configured event, and nothing more.

**No membership, no arm.** "The *configured membership's* direction includes upload" is false when there
is no configured membership, so a transition to usable access with no event configured SHALL fire **neither**
verb. The orchestrator SHALL therefore read the membership's upload posture as a **three-valued** seam —
includes-upload / excludes-upload / **no membership** — collapsing it to a two-valued "enabled" flag in
the composition root is what previously answered *enabled* for an absent membership. That decision is
behavior and SHALL live in the tested orchestrator, like every other row of this table; the root SHALL
contribute only a projection of the current config, with no defaulting of its own.

This is not a nicety. Photo access can be usable while no event is configured — the join gate's
photo-access explainer raises the system dialog **before** the join is confirmed (`join-event`), and a
grant arriving there must not start a producer, because `join-event` requires that "no config is saved
and **no upload producer is enabled** until the user confirms". The membership-less start is also
reachable from a bare Settings grant after a leave. On the app-driven tier a start with no membership
arms a self-re-submitting `BGProcessingTask` heartbeat for an event that does not exist; both tiers'
cycles then skip on the absent config, so the work is inert but the wake is not.

#### Scenario: Provisioning with access already granted starts the producer

- **WHEN** an event is provisioned while photo access is `GRANTED` and the direction includes upload
- **THEN** the orchestrator calls `start()` on the OS-driven producer where composed (else the app-driven one), and calls no verb that destroys dedup state

#### Scenario: Provisioning under a limited grant starts the app-driven producer

- **WHEN** an event is provisioned while photo access is `LIMITED` and the direction includes upload
- **THEN** the orchestrator calls `start()` on the app-driven producer, and the OS-driven producer is not started

#### Scenario: A download-only membership stops the producer

- **WHEN** an event is provisioned while photo access is usable and the direction is download-only
- **THEN** the orchestrator calls `stop()`, and the ledger is left intact

#### Scenario: Provisioning without access defers to the grant

- **WHEN** an event is provisioned while photo access is neither `GRANTED` nor `LIMITED`
- **THEN** the orchestrator calls neither verb, and a later transition to usable access calls `start()` on the permission-selected producer

#### Scenario: A permission flip switches producers stop-first

- **WHEN** photo access transitions from `GRANTED` to `LIMITED` (or back) while an upload-inclusive membership is configured
- **THEN** the orchestrator stops the outgoing producer before starting the incoming one, and at no point are both started

#### Scenario: A permission flip that does not change the resolved kind does not swap

- **WHEN** photo access transitions between usable states on an OS carrying only the app-driven mechanism
- **THEN** resolution yields the same kind, the orchestrator keeps its one producer, and no teardown occurs

#### Scenario: A grant with no event configured arms nothing

- **WHEN** photo access transitions to usable access while no event is configured
- **THEN** the orchestrator calls neither `start()` nor `stop()`, and no background wake is armed

#### Scenario: The join that follows such a grant is what arms the producer

- **WHEN** photo access transitions to usable access with no event configured, and the user then confirms a join whose direction includes upload
- **THEN** the provision transition calls `start()` — the producer is armed at the join, not at the grant

#### Scenario: Leaving stops without wiping

- **WHEN** the user leaves the event
- **THEN** the orchestrator calls `stop()` and the configured event is cleared, while the ledger remains intact so a later join re-uploads nothing already stored

### Requirement: The arm's direction gate lives at the choke point, never at the invoker

An upload arm's participation-direction gate SHALL live at the **choke point** — the one function every
trigger, on every tier, funnels through (`UploadCycle.run()`) — and SHALL NOT be placed at the arm's
**invoker**. No upload job SHALL be created for a membership whose direction excludes upload, at **any**
trigger and on **any** tier.

An invoker-gate is only as sound as its enumeration of invokers, and that enumeration is invalidated
silently by a new tier or a new trigger. This is not hypothetical. `changes/archive/2026-07-07-add-join-direction-mode`,
D3, gated the upload arm by not enabling the producer, reasoning: *"Under `DownloadOnly` the producer is never
enabled, so the OS never invokes the upload extension… No extension code changes."* That holds only where the
OS is the invoker. The app-driven tier (`changes/archive/2026-07-04-add-url-session-upload`) had merged
**three days earlier** and invokes its own cycle from the app process, so a download-only membership uploaded
the member's camera roll on every foreground — while the join gate had promised "you won't share yours". D4 of
the same document gated the **download** arm at its choke point, for reasons it stated explicitly, and is
correct. This requirement supersedes D3.

Placing the gate at the choke point is what makes the cheap mistake impossible. Adding a trigger is one line
in the untested composition root and looks obviously correct; bypassing the choke point means building a
parallel upload path, which nobody does by accident.

**The gate withholds job creation and the discovery walk.** It SHALL NOT withhold the device manifest
write. A membership that contributes nothing shares nothing, and the honest expression of that is an
**empty published manifest** (capability `device-manifest`), not a stale manifest left in place from when
the membership did contribute. Withholding the write was previously justified by three consequences; two of
them do not hold — the completion notify is already conditioned on at least one real completion, and an
empty manifest is already what a contributing membership with nothing in range publishes — and the third,
not silently blanking a previous manifest, is now the *intended* behaviour of a narrowing change
(capability `reconfigure-membership`).

**The gate bounds new work, not settlement.** A cycle the gate declines SHALL, before returning, run the
acknowledgement of terminal upload jobs the OS has **already presented** to this invocation. That pass
creates no upload job, enumerates no library, advances no discovery cursor and issues no network request —
so it takes nothing the gate exists to withhold — and it is the only way the platform's acknowledgement
obligation can be discharged on a tier whose extension is still registered.

**The re-join reconciliation SHALL likewise run ahead of the gate.** It establishes which of this device's
uploaded resources are already on the backend — a fact about bytes, which this system defines as
independent of the selection policy (capability `sync-ledger`) — so gating it on direction would make a
policy-independent fact wait on a policy-dependent branch. It is marker-gated and a no-op on a settled
join, so the cost is bounded to the first cycle after a join, switch, or reinstall. Running it early also
means a member who later re-enables their direction re-uploads nothing.

What SHALL remain behind the gate: upload job creation, the retry pass, and the discovery walk.

For a **contributing** membership the acknowledgement pass SHALL keep its existing position, after the
re-join reconciliation has settled. Its ledger writes record the membership this cycle runs under, and a
reconciliation that has not yet settled may still re-baseline (a switch's `resetTo`), so hoisting the pass
above the reconcile for every cycle would risk labelling rows against the wrong event. The declined cycle
has no such hazard: the drained jobs it settles were created under the same event by the same membership,
whose direction — not whose identity — changed.

Placing the acknowledgement behind the gate was justified by the premise that a non-contributing
membership's extension has been deregistered, so the OS presents nothing. That premise SHALL NOT be relied
upon: it holds only where a producer's `stop()` ran, and a membership reconfigured to exclude upload
deliberately does not stop its producer (capability `reconfigure-membership`, *A disabling change drains
in-flight uploads*). Measured on iOS 26.6: with the extension still registered and jobs outstanding, a
cycle that returned before the acknowledgement pass caused the system to report
`com.apple.photos.error Code=50008` ("appex failed to acknowledge jobs for processing state"), **discard**
the outstanding jobs, and record a failed attempt against the upload-job configuration that defers the
extension by ~300 seconds and escalates with the attempt count. A requirement whose safety rests on a
premise that is false on a shipped path is the failure this clause removes. Expiry: re-measure at the next
iOS major.

A cycle declined by the direction gate SHALL be reported at **routine** severity, not as a fault. A
download-only membership skipping its cycle is the designed outcome of a setting the member chose; the
project's reporting seam turns `Error`-severity log lines into crash-report events (capability
`crash-reporting`), so reporting a routine skip as an error manufactures a fault report on every trigger,
for every non-contributing member, indefinitely.

The gate SHALL be reachable by the tier-neutral tests: it lives in `:domain`'s `feature/upload` zone, not
in a composition root, which the project's hard rule declares wiring-only and untested. Root-placed
enforcement is how this capability's own history records the lifecycle shipping with no owner and no test.

#### Scenario: A download-only membership creates no upload job at any trigger
- **WHEN** a cycle is driven for a membership whose direction excludes upload — by foreground entry, a
  background task, a silent push, a producer start, or an upload completion
- **THEN** no upload job is created, for every one of those triggers

#### Scenario: A download-only membership publishes an empty manifest
- **WHEN** a cycle runs for a membership whose direction excludes upload
- **THEN** an empty device manifest is published for that event, replacing any manifest published while the
  membership did contribute

#### Scenario: The gate holds on a tier whose invoker is the app
- **WHEN** the tier in use invokes the upload cycle from the app process rather than being invoked by the OS
- **THEN** the download-only membership still creates no upload job — the gate does not depend on which
  component invokes the cycle

#### Scenario: Stopping the producer is not the gate
- **WHEN** the producer has been stopped for a download-only membership and a trigger subsequently drives a
  cycle
- **THEN** no upload job is created, because the gate is read at the choke point rather than inferred from
  the producer having been stopped

#### Scenario: A declined cycle still acknowledges the jobs the OS presented
- **WHEN** the OS invokes the cycle for a membership whose direction excludes upload, presenting terminal
  upload jobs created before the direction changed
- **THEN** every presented job is acknowledged and its outcome settled in the ledger, and the cycle still
  creates no upload job, performs no library enumeration and leaves the discovery cursor untouched

#### Scenario: A declined cycle still reconciles the re-join
- **WHEN** a cycle runs for a membership whose direction excludes upload on the first cycle after a re-join,
  switch, or reinstall
- **THEN** the re-join reconciliation runs and seeds the ledger, so re-enabling the direction later
  re-uploads nothing

#### Scenario: A declined cycle whose reconcile defers writes no manifest
- **WHEN** a cycle for a non-contributing membership runs while the re-join reconciliation defers
- **THEN** no manifest is written that cycle, so an unseeded ledger is never published as an empty one

#### Scenario: A declined cycle reports no fault
- **WHEN** a cycle is declined because the membership's direction excludes upload
- **THEN** the outcome is recorded at routine severity and produces no crash-report event, so a
  non-contributing member generates no fault report however many times a trigger fires

### Requirement: The upload cycle owns its entry decision

The upload cycle SHALL read the membership itself and decide what the invocation does, before any library
walk, upload job, device manifest, or notify. The decision SHALL have exactly three outcomes:

- **Skip** — a required input could not be read (protected data unavailable, or — since migration
  step 11a — config-file content this build cannot positively interpret; capability `event-link`,
  *An unreadable config is not an absent config*). Unreadable content includes a foreign envelope
  version and an undecodable current-version payload. The cycle SHALL touch nothing: no reconcile, no
  marker clear, no cursor reset, no jobs. It SHALL complete cleanly; the next cycle retries.
- **Not joined** — there is definitively no usable membership (no config file by the not-found
  error class and — while the read-only fallback lasts — no legacy Keychain item, or a legacy
  item that does not decode (the legacy-item rule, Keychain-side only), or no baked
  host). The cycle SHALL run the
  leave-side reconciliation, which clears the `joinedEventId` marker (capability
  `event-rejoin-reconciliation`), and SHALL create no upload job.
- **Run** — joined and configured. The cycle SHALL proceed to its contribution gate and phases.

A composition root SHALL NOT make this decision. A root SHALL supply only the platform reads the decision
consumes — the membership read, the device-identity probe, and the build-time host — and the shared,
tested decision function SHALL combine them. This is the same containment `reconcile` and the
`SelectionPolicy` already have, and for the same reason: an upload tier's root is wiring-only and
untested by project rule,
so a decision placed there reaches whichever tiers its author happened to enumerate.

The **translation** of those reads into the decision's inputs SHALL itself exist exactly once, in the
shared composition (`uploadCore`, `:domain` `compose/`) — not once per root. It SHALL be **port-pure**:
one fresh three-state `ConfigReader.read()` per cycle, the identity probe, and the host read, and
nothing else. In particular it SHALL NOT refresh any adapter-held read-model state (such as the
UI-facing `ConfigSource` `StateFlow`) as a side effect of gating a cycle: repairing a `StateFlow`
seeded while protected data was unavailable is the app process's trigger flows' concern — every
OS-callback flow re-reads the membership before acting (migration step 12; see `ios-app-shell`,
*Background triggers re-read the membership and fail cleanly before first unlock*) — not the entry
gate's. (Decision record: `changes/archive/establish-shared-composition` D1 — the previously-shipped
per-root translations diverged on exactly this side effect, with the gate outcome provably identical.)

The decision SHALL be reachable per cycle, not resolved once at construction: a tier whose process
outlives a cycle SHALL re-read the membership on each run so a join, leave, or switch takes effect without
a relaunch.

An unresolvable device identity SHALL produce **Skip**, never **Not joined**. Resolving the identity can
fail exactly as the membership read can — the identity is a Keychain item and the membership a
protected App-Group file, and both
are unreadable in the same locked-device windows — and every outcome needs it. "I could not look" is
not "no identity" (capability `device-identity`, which never reports absence: an absent item mints).

#### Scenario: An unreadable membership skips without touching state
- **WHEN** the cycle's membership read reports unreadable
- **THEN** the cycle completes cleanly, having created no upload job, run no reconciliation, cleared no
  marker, and reset no cursor

#### Scenario: An unresolvable device identity skips, and does not read as a leave
- **WHEN** the device identity cannot be resolved because protected data is unavailable
- **THEN** the cycle skips, the `joinedEventId` marker is left intact, and the identity is not re-minted

#### Scenario: A definitely-absent membership reconciles the leave side
- **WHEN** the cycle's membership read reports definitively no usable membership
- **THEN** the leave-side reconciliation runs, the `joinedEventId` marker is cleared, and no upload job is
  created

#### Scenario: The decision holds on every tier
- **WHEN** any tier runs a cycle from any trigger with an unreadable membership
- **THEN** the outcome is Skip, regardless of which tier or trigger invoked it

#### Scenario: A long-lived tier re-reads the membership each cycle
- **WHEN** a tier whose process survives across cycles runs a cycle after the membership changed
- **THEN** the cycle acts on the current membership, without a relaunch

#### Scenario: The entry-gate translation is one implementation
- **WHEN** any tier (or the world harness) assembles an upload cycle
- **THEN** its entry gate is the shared `uploadCore` translation over that tier's ports — a fresh
  three-state read per cycle with no adapter read-model refresh — so no tier can carry gate semantics
  another tier lacks

### Requirement: Every selection and side-effect port is answered at the call site

The upload cycle SHALL require each port that shapes what a member contributes or what a completed cycle
emits — the device-manifest hook, the echo-suppression source, the denylisted-album source, the
completion-notify hook, the membership read, the reconciliation, and the contribution. None SHALL carry a
default.

A permissive default on such a port is an unstated answer: it is how a tier ships without a policy the
other tier has, and the resulting failure is the invisible kind this project is built against — a photo
that never enters the event, or a denylisted photo that does, with the screen reading "In sync"
throughout. Requiring the port does **not** require a tier to have the capability; a tier without one
supplies the empty answer explicitly, so the answer is recorded at the call site and reviewable in the
diff rather than inherited in silence.

#### Scenario: A cycle cannot be constructed without stating its policy
- **WHEN** a composition site constructs an upload cycle without supplying a selection or side-effect port
- **THEN** it does not compile

#### Scenario: An empty answer is legal when stated
- **WHEN** a tier has no denylisted-album source and supplies an empty one explicitly
- **THEN** the cycle runs, admitting all albums, and the choice is visible at the call site

### Requirement: Exactly one producer started per process

At most one `UploadProducer` SHALL be started at any time, and the tier-neutral orchestrator SHALL be the
only component that starts or stops one. A mechanism switch SHALL be **stop-then-start**: the outgoing producer's
`stop()` completes before the incoming producer's `start()` — the OS-driven producer's `stop()` is what
deregisters the extension, which is what actually prevents a second `LedgerWriter` over the App-Group
ledger (`sync-ledger`).

The invariant SHALL be **structural**: because the orchestrator holds at most one producer reference
("The upload mechanism is resolved, never selected"), starting two has no expression. Structural
exclusion was the original guarantee and was given up when the mechanism choice became an input of
**runtime** permission, which no once-per-process *construction* decision could express. Re-resolution
expresses it, so the compile-time guarantee and the runtime dependence are no longer in tension.

Because the invariant is structural, the `:test:architecture` guard (capability `architecture-guards`)
SHALL be retargeted rather than retired, at the two places the risk moved: the resolver's cells, and the
orchestrator's now-stateful transitions.

The invariant holds **however** the app-driven tier comes to be selected: whenever it is the started
producer on a device whose OS supports the OS-driven one, the PhotoKit extension SHALL NOT be registered.
This was once stated against a development tier-force flag, which no longer exists — production Kotlin
declares no launch triggers at all (capability `architecture-guards`). A **limited** photo grant reaches
that state, and so does a development mechanism override ("A mechanism override is a runtime input a
shipped build cannot carry"); the requirement is stated against the state rather than any mechanism that
produces it, and survives the next mechanism too.

Not registering is **not sufficient**. The OS's upload-job configuration record is keyed by bundle id and
survives relaunch and reinstall, so a process that merely declines to register still runs behind a live
extension a previous process or a previous installation left. Whenever the app-driven producer is the
started one on an OS that supports the OS-driven mechanism, any surviving registration SHALL be
**deregistered**.

#### Scenario: Only the resolved producer runs
- **WHEN** the app runs on an OS where more than one mechanism exists
- **THEN** at most one producer is started at any time, it is the one resolution yielded, and the other
  mechanism is not invoked

#### Scenario: Starting both cannot be expressed
- **WHEN** the orchestrator's producer field is inspected
- **THEN** it names at most one producer, so no code path can start two

#### Scenario: The switch is stop-then-start
- **WHEN** the orchestrator switches producers on a resolution change
- **THEN** the outgoing producer is stopped (the OS-driven one deregistering its extension) before the
  incoming producer starts

#### Scenario: The app-driven producer runs without the extension registered
- **WHEN** the app-driven producer is the selected one on a device whose OS supports the OS-driven tier —
  as a limited photo grant makes it
- **THEN** the PhotoKit upload extension is not registered — and if a previous process or installation
  registered it, it is deregistered before the app-driven producer starts — with only the app-driven
  producer started

### Requirement: The upload mechanism is resolved, never selected

The system SHALL determine which upload mechanism runs by a **pure, exhaustively-tested resolution**
from OS facts, current photo permission, and whether the app-driven tier is forced, to a mechanism
**kind**. A composition-supplied factory SHALL map a kind to an instance. The tier-neutral orchestrator
SHALL hold **at most one** producer reference at any time, and SHALL obtain a new one only by
re-resolving when a resolution input changes.

The **transport binding** the app-driven mechanism uses is a different axis and SHALL NOT enter this
resolution: it is fixed by the compilation target (`ios-url-session-upload`, "The transport binding is
fixed by the compilation target"), and `module-architecture` requires that a fact fixed by the
compilation target is not re-derived at runtime nor admitted into this function. Which mechanism runs
stays a genuine runtime decision; which session kind it transfers over is not a decision at all.

Resolution SHALL be total, and SHALL NOT yield a kind whose mechanism this OS cannot run: the OS-driven
mechanism's registration selector does not exist below iOS 26.1, so a cell yielding it there would trap
and abort the process. The resolver — not a composition root — SHALL own this, because a root is
wiring-only and untested by project rule.

Presence and runnability are **separate facts**. "This OS has no such mechanism" and "the mechanism is
present but this build must not run it" SHALL NOT share an encoding. Collapsing them is what previously
left a present mechanism with no route to its own teardown on a forced build: the OS-driven producer was
not constructed, so nothing could call the `stop()` that deregisters its extension, while the OS's
upload-job configuration record — keyed by bundle id and surviving relaunch **and** reinstall — remained.

The factory SHALL cache an instance whose platform demands a process-lifetime singleton. On every shipped
binary the app-driven mechanism owns a background `URLSession` whose identifier must stay stable and whose
invalidation is terminal (`ios-url-session-upload`, "Cancellation never invalidates the background
session"), so re-resolving to that kind SHALL return the same instance rather than constructing a second
one. The caching SHALL NOT be conditioned on the transport binding: on `iosSimulatorArm64`, where the
session is a default one and its identifier is inert, a second instance would still mean two live sessions
and two task registries for one mechanism, so the same single instance SHALL be returned there too.

Where an OS carries more than one mechanism, **each** resolved mechanism SHALL relinquish what the other
leaves behind, before it starts. Both leave state the OS keeps across process death — the OS-driven one a
configuration record keyed by bundle id, the app-driven one in-flight background transfers and a submitted
background task — so a process that has just launched may be running behind work it never started.
Relinquishing the OS-driven mechanism on the way to the app-driven one SHALL be **deregistration only**
(see the repair carve-out in "Upload producer seam has no destructive verb"); relinquishing the app-driven
mechanism SHALL be its ordinary `stop()`.

Stopping the arm SHALL likewise stop **every** mechanism the composition can yield, not only the one
currently held: a mechanism this process never started can still have work outstanding on its behalf.

#### Scenario: Starting the OS-driven mechanism cancels app-driven work left by an earlier process

- **WHEN** the OS-driven mechanism is resolved on a device where a previous process left in-flight
  app-driven transfers or a submitted background task
- **THEN** those are cancelled before the OS-driven mechanism starts, so only one process writes records

#### Scenario: A forced build on an OS-driven-capable device relinquishes the registration

- **WHEN** the app-driven tier is forced on a device whose OS supports the OS-driven mechanism, and an
  upload-inclusive membership is provisioned under usable access
- **THEN** resolution yields the app-driven kind for that OS, whose producer deregisters the OS-driven
  extension before it begins pumping — so the OS cannot invoke the extension behind the running tier

#### Scenario: The same cell serves a downgrade to limited access

- **WHEN** photo access transitions from `GRANTED` to `LIMITED` on a device whose OS supports the
  OS-driven mechanism
- **THEN** resolution yields that same app-driven kind, and the extension is deregistered by the same
  mechanism rather than by a separate rule

#### Scenario: Resolution never yields an unrunnable mechanism

- **WHEN** every combination of OS facts, permission, and forced state is resolved
- **THEN** no combination yields the OS-driven kind on an OS that lacks it

#### Scenario: The transport binding is not a resolution input

- **WHEN** the resolver's inputs are enumerated
- **THEN** the session kind the app-driven mechanism transfers over is not among them, and no cell varies
  by it

#### Scenario: Re-resolving to the app-driven kind reuses its instance

- **WHEN** the resolved kind changes away from the app-driven mechanism and later back to it
- **THEN** the same instance is obtained, its session was never invalidated, and uploads
  resume without aborting the process
### Requirement: A mechanism override is a runtime input a shipped build cannot carry

Resolution SHALL accept an optional **override** naming a mechanism kind, read fresh at every resolution
rather than captured once. Its absence SHALL mean "not overridden", and resolution SHALL remain total
without it — which is the production case on every shipped build.

The override SHALL name a mechanism **kind**, not a Boolean. Pinning the OS-driven mechanism is as
meaningful as pinning the app-driven one: on a host that would resolve it anyway, pinning it explicitly is
what makes a run reproducible rather than dependent on the host's OS version. A Boolean cannot express it
and cannot be widened later without changing the seam.

**A build without test equipment SHALL be structurally incapable of carrying an override** — not merely
unlikely to. The override's source SHALL be supplied by the build-time-only control channel, whose source
is absent from a build made without its build property, so nothing in a shipped binary can establish one.

That structural form is required rather than preferred, because the alternative was tried and is worse: a
source that **persists** — a file in the shared container — can hand an override to a build that never
established it, since the container survives an application update though not a delete-and-reinstall
(measured on device). Such a design needs a process-scoping rule to refuse a foreign value, and a rule can
only *bound* that hazard where a structural answer *removes* it. Where the writer cannot exist in the
binary that must not honour the value, there is nothing to refuse.

It follows that the override SHALL NOT be persisted anywhere a build without the control channel reads.

#### Scenario: A shipped build cannot be overridden

- **WHEN** a build made without the control channel runs on any OS under any permission
- **THEN** no override is in effect, because nothing in that binary is able to establish one

#### Scenario: Either mechanism can be pinned

- **WHEN** the override names the OS-driven mechanism on an OS that carries it
- **THEN** resolution yields that mechanism, so a run does not depend on which one the host would have
  resolved by default

#### Scenario: Absence of an override is not a failure

- **WHEN** no override has been established
- **THEN** resolution proceeds from OS facts and permission alone, and yields a mechanism for every input
  combination

### Requirement: A mechanism is always resolved

Resolution SHALL yield a producer for **every** combination of inputs, including those under which no
upload work may occur. Unusable photo access SHALL resolve to an **idle** mechanism — a producer that
declines every trigger while honouring the platform contract of each — and SHALL NOT resolve to absence.

An OS trigger carries a completion handler whose release the system waits on; an unanswered handler costs
the app its future background wakes (`ios-app-shell`, "OS completion handlers are released only after
their work completes"). Routing a trigger to an absent mechanism strands that handler. The idle mechanism
is a deliberate collapse that names its consequence — nothing is uploaded, every handler is still
released — rather than a silent one (`module-architecture`, "Absence is never silent").

#### Scenario: A background wake with no usable access still completes

- **WHEN** the OS delivers a background trigger while photo access is `NOT_DETERMINED` or `DENIED`
- **THEN** no upload work is performed and the OS completion handler is still released

### Requirement: Triggers are delivered to the mechanism and declined explicitly

App-side upload triggers SHALL be delivered to the resolved mechanism **unconditionally** — foreground
entry, a silent push, a background-task heartbeat, and a photo selection change. The caller SHALL NOT
decide which mechanism is interested; each mechanism SHALL state its own answer, and a mechanism that
does nothing SHALL say so **explicitly at its definition site**.

The trigger surface SHALL NOT carry defaults. A permissive default is an unstated answer, and a tier that
inherits one has no reviewable record of the choice (see "Every selection and side-effect port is
answered at the call site", which rules the same way for the cycle's ports).

Deciding at the caller is an **invoker-gate**, and this capability has already ruled on that shape ("The
arm's direction gate lives at the choke point, never at the invoker"): the enumeration of invokers is
invalidated silently by a new tier or a new trigger. Whether a mechanism responds to a trigger — and
whether responding would read the photo library at all — is a property only that mechanism knows.

Each trigger SHALL be a `suspend` function that returns when its work is done and SHALL NOT accept an OS
completion handler. The handler is held by the entry point that received it, for the deadline named for
that OS wake, so no mechanism can fail to release one.

#### Scenario: A mechanism the OS schedules declines an app-side pump

- **WHEN** a foreground trigger reaches a mechanism whose uploads the OS itself schedules
- **THEN** the mechanism performs no pump, its declining answer is stated in its own implementation, and
  the trigger completes

#### Scenario: A new mechanism cannot inherit silence

- **WHEN** a mechanism is added without stating an answer for every trigger
- **THEN** it does not compile
### Requirement: Settling with the platform is owed regardless of the cycle's other outcomes

The upload cycle SHALL settle with the platform — drain the outcomes it is holding and adjudicate them
— on **every** cycle that reaches a usable membership, before and independently of every later
decision the cycle makes. In particular it SHALL do so when the re-join reconciliation defers, exactly
as it already does when the direction gate declines.

The obligation is owed to the platform for work it has already presented, and it does not depend on
whether this membership still contributes, or on whether the ledger has been seeded yet. Measured on
iOS 26.6: with the extension still registered and jobs outstanding, a cycle that returned before the
acknowledgement pass caused the system to report `com.apple.photos.error Code=50008` ("appex failed to
acknowledge jobs for processing state"), **discard** the outstanding jobs, and record a failed attempt
against the upload-job configuration that defers the extension by ~300 seconds and escalates with the
attempt count. Expiry: re-measure at the next iOS major.

Settling creates no upload work and publishes nothing: it enumerates nothing, touches no discovery
cursor, and writes no manifest. Suppressing the manifest write on a deferred reconciliation stays
required (capability `device-manifest`) and is unaffected by this.

#### Scenario: A deferred reconciliation still settles

- **WHEN** the re-join reconciliation defers because the device's stored-file listing failed or timed
  out, on a contributing membership
- **THEN** the cycle still settles with the platform, and still writes no manifest, creates no upload
  job, and leaves the discovery cursor untouched

#### Scenario: A declined direction still settles

- **WHEN** the membership's direction excludes upload
- **THEN** the cycle still settles with the platform

#### Scenario: An unusable membership settles nothing

- **WHEN** the entry gate reports the membership unreadable, or definitively absent
- **THEN** the cycle settles with no platform at all, because settling requires the configuration the
  gate could not supply

### Requirement: The cycle's publication is decided by its outcome

The upload cycle SHALL decide what it publishes from its own stated outcome, in one place, rather than
by which statement returned. What it publishes means the event-album placement, the enumeration audit
line, the device manifest, the completion notify, and the promotion of uploaded rows. The decision
SHALL be exhaustive over the outcomes a cycle can have, so a new outcome cannot inherit a publication
policy nobody chose for it.

No path SHALL be able to return a cycle result without passing through that decision.

This exists because five publications were previously reachable only by falling through to the end of
the cycle, so any early return silently withheld all five — and the two early returns that a device
with a backlog takes on every cycle withheld them permanently, with no error and no log line.

#### Scenario: A new cycle outcome must state what it publishes

- **WHEN** a new outcome is added to the cycle's result vocabulary and the publication decision is not
  updated
- **THEN** the build fails, because the decision is exhaustive with no fallback branch

#### Scenario: Every exit publishes

- **WHEN** a cycle ends by any route — unreadable membership, no membership, deferred reconciliation,
  declined direction, job limit reached, or fully drained
- **THEN** the publication decision runs for that outcome, publishing exactly what that outcome calls
  for
