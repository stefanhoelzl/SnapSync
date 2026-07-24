# upload-lifecycle Specification

## Purpose

The **tier-neutral upload arm**: which producer verb fires on which membership transition (provision,
event switch, permission grant, direction change, leave), and the invariant that **no transition ever
destroys durable dedup state**. Each upload tier supplies the mechanism behind a two-verb `UploadProducer`
seam (`start` / `stop`); this capability owns the decision, and it owns it in one tested, platform-free
place.

It exists because the upload lifecycle previously had **no owner**. It was smeared across the two tier
specs and the iOS composition root — a module the project's own hard rule declares wiring-only and
untested — so no contract described it and no test could reach it. When a second upload tier arrived, the
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
## Requirements
### Requirement: Upload producer seam has no destructive verb

The system SHALL express the upload arm as a platform-free `UploadProducer` seam in `:domain`'s
`feature/upload` zone (package `app.snapsync.feature.upload`) with exactly **two** verbs:

- `start()` — begin or resume uploading for the currently-configured membership.
- `stop()` — cease uploading. It SHALL NOT destroy **dedup state**: it SHALL NOT clear the ledger and
  SHALL NOT delete stored bytes.

There SHALL be **no** destructive verb on the seam. No lifecycle transition — provision, re-provision,
event switch, permission change, direction change, or leave — SHALL clear the ledger. Durable dedup state
is device-global (`sync-ledger`), and divergence from storage is repaired by reconciliation
(`event-rejoin-reconciliation`), never by a lifecycle wipe.

The property being defended is **dedup**: the proof that a photo is already in the event. Destroying it
re-uploads a member's whole post-cutoff library — the failure this project exists to prevent. The ledger's
`COMPLETED` rows and the stored bytes are that proof; the **discovery cursor is not**. A cleared cursor
costs one full re-enumeration, which finds nothing new, because dedup lives in the ledger it did not touch.

A tier's `stop()` MAY therefore clear its **discovery cursor**, but **only** as a repair for damage its own
mechanism causes, and **only** where `COMPLETED` rows survive so nothing already stored re-uploads. The
condition is the rule, not the tier: clearing a cursor for tidiness, or where dedup state would not survive
it, remains forbidden. (The PhotoKit tier is the standing instance — the OS's extension-disable wipes every
in-flight job, and `clearRequested()` alone leaves the cleared photos un-rediscoverable behind a settled
cursor. That repair is required by `ios-photokit-upload`, which owns it.)

Each tier SHALL supply one `UploadProducer` implementation binding these verbs to its own mechanism.

#### Scenario: The seam exposes no way to destroy dedup state

- **WHEN** the `UploadProducer` seam is inspected
- **THEN** it exposes only `start()` and `stop()`, and no lifecycle caller can clear the ledger through it

#### Scenario: Stopping preserves dedup state

- **WHEN** `stop()` is called on either tier
- **THEN** in-flight uploads cease, but every ledger row and every stored object is left intact

#### Scenario: A tier may clear its own cursor to repair its own mechanism

- **WHEN** a tier's `stop()` clears its discovery cursor as a repair for jobs its own mechanism wiped, and its `COMPLETED` ledger rows survive
- **THEN** that is permitted: the next cycle re-enumerates fully and creates no upload job for anything already stored

#### Scenario: Clearing a cursor that dedup depends on is still forbidden

- **WHEN** a tier's `stop()` would clear a cursor without its `COMPLETED` rows surviving, or for any reason other than repairing its own mechanism
- **THEN** that is forbidden — the carve-out is conditioned on dedup surviving, not on which tier is asking

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
Where more than one producer is composed (capability `ios-url-session-upload`, "Per-version tier
selection"), the orchestrator SHALL start the producer **selected by the current permission** — the
OS-driven producer under `GRANTED`, the app-driven producer under `LIMITED` (the OS never invokes the
extension under a partial grant; capability `ios-photokit-upload`) — and SHALL hold the
exactly-one-started invariant (see "Exactly one producer started per process"). The orchestrator SHALL
bind the transitions as follows, where the upload arm is enabled exactly when photo access is usable
**and** the configured membership's direction includes upload (`join-event`):

| Transition | Action |
| --- | --- |
| provision / re-provision, arm enabled | `start()` on the permission-selected producer |
| provision / re-provision, access usable but direction is download-only | `stop()` |
| provision / re-provision, access not usable | neither (the grant transition will drive it) |
| transition to usable access (`GRANTED` or `LIMITED`), arm enabled | `start()` on the permission-selected producer |
| transition between usable states (`GRANTED` ↔ `LIMITED`), arm enabled | switch: `stop()` the outgoing producer, then `start()` the incoming one |
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
**invoker**. No upload job SHALL be created, and no device manifest written, for a membership whose
direction excludes upload, at **any** trigger and on **any** tier.

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

The gate SHALL be reachable by the tier-neutral tests: it lives in `:domain`'s `feature/upload` zone, not
in a composition root, which the project's hard rule declares wiring-only and untested. Root-placed
enforcement is how this capability's own history records the lifecycle shipping with no owner and no test.

#### Scenario: A download-only membership creates no upload job at any trigger
- **WHEN** a cycle is driven for a membership whose direction excludes upload — by foreground entry, a
  background task, a silent push, a producer start, or an upload completion
- **THEN** no upload job is created and no device manifest is written, for every one of those triggers

#### Scenario: The gate holds on a tier whose invoker is the app
- **WHEN** the tier in use invokes the upload cycle from the app process rather than being invoked by the OS
- **THEN** the download-only membership still creates no upload job — the gate does not depend on which
  component invokes the cycle

#### Scenario: Stopping the producer is not the gate
- **WHEN** the producer has been stopped for a download-only membership and a trigger subsequently drives a
  cycle
- **THEN** no upload job is created, because the gate is read at the choke point rather than inferred from
  the producer having been stopped

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

At most one `UploadProducer` SHALL be started at any time where the composition constructs more than
one (iOS ≥26.1 constructs both the OS-driven and the app-driven producer; below 26.1 only the
app-driven one exists), and the tier-neutral orchestrator SHALL be the only component
that starts or stops either. A mechanism switch SHALL be **stop-then-start**: the outgoing producer's
`stop()` completes before the incoming producer's `start()` — the OS-driven producer's `stop()` is what
deregisters the extension, which is what actually prevents a second `LedgerWriter` over the App-Group
ledger (`sync-ledger`).

This replaces the prior structural exclusion (only one producer constructed) because the mechanism
choice is now an input of **runtime** permission, which no once-per-process construction decision can
express. The invariant's essence — one writer at a time — is unchanged; its enforcement moves from the
compiler to a `:test:architecture` guard (capability `architecture-guards`). The development
tier-force flag retains its meaning: forcing the app-driven tier SHALL NOT register the PhotoKit
extension.

#### Scenario: Only the permission-selected producer runs
- **WHEN** the app runs on iOS ≥26.1 with both producers composed
- **THEN** at most one producer is started at any time, selected by current permission, and the other's
  mechanism is not invoked

#### Scenario: The switch is stop-then-start
- **WHEN** the orchestrator switches producers on a permission flip
- **THEN** the outgoing producer is stopped (the OS-driven one deregistering its extension) before the
  incoming producer starts

#### Scenario: Forcing the app-driven tier does not enable the extension
- **WHEN** the app-driven tier is forced on a device whose OS supports the OS-driven tier
- **THEN** the PhotoKit upload extension is not registered, and only the app-driven producer is started
