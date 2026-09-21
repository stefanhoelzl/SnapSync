## MODIFIED Requirements

### Requirement: Upload producer seam has no destructive verb

The system SHALL express the upload arm's **lifecycle** as a platform-free `UploadProducer` seam in `:domain`'s
`feature/upload` zone (package `app.snapsync.feature.upload`) with exactly **two** verbs:

- `start()` — begin or resume uploading for the currently-configured membership.
- `stop()` — cease uploading. It SHALL NOT destroy **dedup state**: it SHALL NOT clear the ledger and
  SHALL NOT delete stored bytes.

There SHALL be **no** destructive verb on the seam. Neither verb clears the ledger, and no caller can
clear it through the seam.

**The ledger is cleared at exactly two membership transitions, and never by the seam.** The upload ledger
is the **current membership's share set** (`sync-ledger`): empty while unjoined, loaded at a join, cleared
at a leave. This **reverses** this requirement's earlier rule that no lifecycle transition — provision,
re-provision, event switch, permission change, direction change, or leave — clears the ledger, and the
reversal covers a **provision** as well as a leave:

- a **leave** stops uploads and then clears the upload ledger (`leave-event`);
- a **provision into a new membership** — a first join, or a switch (a provision of a different event while
  one is joined) — clears the ledger and loads it from the device's stored-file listing before its config is
  saved, and a switch first **stops uploads** before that load (`join-event`). A provision therefore now carries a stop (on
  a switch) and a ledger reset (on a first join or a switch), which this requirement previously forbade.

Every other transition — a **re-provision of the joined event**, a permission change, a direction change,
a reconfigure — SHALL NOT clear or reset the ledger. The clear and the load are performed by the membership
use-cases over the ledger store (`LeaveEvent`, the join-time share-set load), never by a producer verb and
never by the orchestrator, which holds no ledger. The download store is not cleared by any of them
(`download-store`).

The trigger surface ("Triggers are delivered to the mechanism and declined explicitly") SHALL be a
**separate** seam on the same object, so this lifecycle seam keeps exactly the two verbs above and the
orchestrator is given no trigger to invoke.

The property being defended is **dedup**: the proof that a photo is already in the event. Losing it
re-uploads a member's whole in-window library — the failure this project exists to prevent. The stored
bytes are that proof. The ledger's `COMPLETED` rows are this membership's **local copy** of it, rebuilt at
each join from the per-device listing, which is why a leave and a join may clear them and nothing else may:
a clear anywhere a load does not follow would lose the copy with nothing to restore it. Nothing else a
mechanism persists is part of that proof.

No mechanism needs a destructive verb as a repair either: the damage a stop can leave behind is
`REQUESTED` rows no transfer will settle, and each mechanism repairs those in its own **`start()`** by demoting them to `DISCOVERED`
(`ios-photokit-upload`, `ios-url-session-upload`), which the ledger's work read returns without a walk. A
repair belongs to the start because the start is the one moment a mechanism knows no other transfer is still
carrying those rows, and because every path back to uploading passes through one.

(This seam previously permitted a `stop()` to clear its discovery cursor as a repair for jobs its own
mechanism wiped. Its only instance was the PhotoKit disable, whose bulk *delete* of `REQUESTED` rows could
not be recovered without a re-enumeration. With the rows demoted instead of deleted, the permission had no
use and was withdrawn; the discovery cursor itself has since been removed.)

Each tier SHALL supply one `UploadProducer` implementation binding these verbs to its own mechanism.

#### Scenario: The seam exposes no way to destroy dedup state

- **WHEN** the `UploadProducer` seam is inspected
- **THEN** it exposes only `start()` and `stop()`, and no lifecycle caller can clear the ledger through it

#### Scenario: Stopping preserves dedup state

- **WHEN** `stop()` is called on either tier
- **THEN** in-flight uploads cease, but every ledger row and every stored object is left intact

#### Scenario: Stopping touches no ledger row

- **WHEN** `stop()` is called on either tier, including as part of a switch or a leave
- **THEN** the stop itself leaves every ledger row exactly as it was; any clear that follows is the leave's
  or the join-time load's own step, not the stop's

#### Scenario: A leave clears the upload ledger after stopping

- **WHEN** the user leaves the event
- **THEN** uploads are stopped first and the upload ledger is then cleared, while the download store's rows
  are left untouched

#### Scenario: A switch provision stops, then replaces the ledger

- **WHEN** a provision configures an event different from the joined one
- **THEN** uploads are stopped, the ledger is then cleared and loaded from the device's stored-file listing,
  and only then is the new config saved, so the new membership never sits over the previous membership's
  rows

#### Scenario: A re-provision of the joined event touches no ledger row

- **WHEN** the event that is already joined is provisioned again, or photo permission or the membership's
  direction changes
- **THEN** no ledger row is cleared, reset, or loaded — the producer verbs the orchestration table assigns
  to that transition are the only effect on the upload arm

#### Scenario: Rows a stop leaves stranded are repaired by the next start

- **WHEN** a `stop()` leaves `REQUESTED` rows that no transfer will settle, and a mechanism is later started
- **THEN** that start demotes those rows to `DISCOVERED`, and the next cycle re-creates their uploads from the
  ledger's work read, without the walk re-deriving them

### Requirement: Lifecycle orchestration is tier-neutral and tested

The decision of **which verb fires on which transition** SHALL live in a tier-neutral orchestrator in
`:domain`'s `feature/upload` zone, not in the app composition root, and SHALL be tested in `commonTest`
(running on both JVM and `iosSimulatorArm64`) against fake `UploadProducer`s, so it is exercised on JVM
**and** `iosSimulatorArm64` rather than only inside an iOS process. The orchestrator SHALL translate
membership and permission transitions into `start()`/`stop()` and nothing else: it holds no ledger and
no storage handle, and the seam gives it no verb that could reach one. The upload ledger's clear at a leave
and its clear-then-load at a provision into a new membership are the membership use-cases' own steps
(`leave-event`, `join-event`; see "Upload producer seam has no destructive verb"), sequenced around the
orchestrator's verbs, never performed by it.

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
| switch (provision of a different event while one is joined) | `stop()` first, before the join-time load and the save of the new config; then the provision row that applies |
| leave | `stop()` |

Leave SHALL be `stop()`, then clearing the upload ledger, then clearing the configured event (then the
best-effort backend notify, capability `leave-event`), and nothing more. A switch SHALL be a leave followed
by a join: its `stop()` comes first, then the join's clear-then-load of the ledger replaces the previous
membership's share set, and only then is the new config saved (capability `join-event`).

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
- **THEN** the orchestrator calls `stop()`, and the stop itself leaves every ledger row intact

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

#### Scenario: Leaving stops, then clears the upload ledger

- **WHEN** the user leaves the event
- **THEN** the orchestrator calls `stop()`, the upload ledger is then cleared, and the configured event is
  then cleared — and a later join re-uploads nothing already stored, because that join loads the ledger from
  the device's stored-file listing

#### Scenario: A switch stops before the new membership is saved

- **WHEN** a provision configures an event different from the joined one
- **THEN** the orchestrator's `stop()` runs before the join-time load and before the new config is saved,
  and the provision row for the new membership then applies

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
creates no upload job, enumerates no library and issues no network request —
so it takes nothing the gate exists to withhold — and it is the only way the platform's acknowledgement
obligation can be discharged on a tier whose extension is still registered.

**The cycle holds no re-join reconciliation.** Which of this device's uploaded resources are already on the
backend — a fact about bytes, independent of the selection policy (capability `sync-ledger`) — is
established once, at the join, by the join-time load of the ledger from the device's stored-file listing
(capability `join-event`), for every direction. No cycle re-derives it, so nothing about it runs ahead of
the gate or behind it, and a member who later re-enables their direction still re-uploads nothing already
stored.

What SHALL remain behind the gate: upload job creation, the retry pass, and the discovery walk.

For a **contributing** membership the acknowledgement pass SHALL keep its existing position, behind the
direction gate and after the retry pass. The declined cycle runs it before returning: the drained jobs it
settles were created under the same event by the same membership, whose direction — not whose identity —
changed.

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
  creates no upload job and performs no library enumeration

#### Scenario: A declined cycle fetches no listing
- **WHEN** a cycle runs for a membership whose direction excludes upload, on the first cycle after a join or
  a switch
- **THEN** the cycle fetches no stored-file listing and resets no ledger row — the ledger was already loaded
  at the join, so re-enabling the direction later re-uploads nothing already stored

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
  version and an undecodable current-version payload. The cycle SHALL touch nothing: no ledger write, no
  jobs. It SHALL complete cleanly; the next cycle retries.
- **Not joined** — there is definitively no usable membership (no config file by the not-found
  error class and — while the read-only fallback lasts — no legacy Keychain item, or a legacy
  item that does not decode (the legacy-item rule, Keychain-side only), or no baked
  host). The cycle SHALL create no upload job and SHALL write, clear, or reset no ledger row. Clearing
  the upload ledger belongs to the leave itself (capability `leave-event`), an explicit app action; the
  cycle does not detect or repair a membership change, and there is no leave-side step for it to run.
- **Run** — joined and configured. The cycle SHALL proceed to its contribution gate and phases.

A composition root SHALL NOT make this decision. A root SHALL supply only the platform reads the decision
consumes — the membership read, the device-identity probe, and the build-time host — and the shared,
tested decision function SHALL combine them. This is the same containment the `SelectionPolicy` already
has, and for the same reason: an upload tier's root is wiring-only and
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
- **THEN** the cycle completes cleanly, having created no upload job and written no ledger row

#### Scenario: An unresolvable device identity skips, and does not read as a leave
- **WHEN** the device identity cannot be resolved because protected data is unavailable
- **THEN** the cycle skips, no ledger row is written, and the identity is not re-minted

#### Scenario: A definitely-absent membership creates and clears nothing
- **WHEN** the cycle's membership read reports definitively no usable membership
- **THEN** no upload job is created and no ledger row is written, cleared, or reset — the cycle runs no
  leave-side step

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
completion-notify hook, the membership read, and the contribution. None SHALL carry a default. (The cycle
formerly also required a re-join reconciliation port; it has none, because the ledger is loaded at the
join rather than reconciled in a cycle — capability `join-event`.)

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

### Requirement: Settling with the platform is owed regardless of the cycle's other outcomes

The upload cycle SHALL settle with the platform — drain the outcomes it is holding and adjudicate them
— on **every** cycle that reaches a usable membership, before and independently of every later
decision the cycle makes. In particular it SHALL do so when the direction gate declines.

The obligation is owed to the platform for work it has already presented, and it does not depend on
whether this membership still contributes. Measured on
iOS 26.6: with the extension still registered and jobs outstanding, a cycle that returned before the
acknowledgement pass caused the system to report `com.apple.photos.error Code=50008` ("appex failed to
acknowledge jobs for processing state"), **discard** the outstanding jobs, and record a failed attempt
against the upload-job configuration that defers the extension by ~300 seconds and escalates with the
attempt count. Expiry: re-measure at the next iOS major.

Settling creates no upload work and publishes nothing: it enumerates nothing and writes no manifest.

#### Scenario: A declined direction still settles

- **WHEN** the membership's direction excludes upload
- **THEN** the cycle still settles with the platform

#### Scenario: An unusable membership settles nothing

- **WHEN** the entry gate reports the membership unreadable, or definitively absent
- **THEN** the cycle settles with no platform at all, because settling requires the configuration the
  gate could not supply

### Requirement: The cycle's publication is decided by its outcome

The upload cycle SHALL decide what it publishes from its own stated outcome, in one place, rather than
by which statement returned. What it publishes means the enumeration audit line and the device manifest.
The decision SHALL be exhaustive over the outcomes a cycle can have, so a new outcome cannot inherit a
publication policy nobody chose for it.

It publishes no completion notify, no promotion of uploaded rows, and no event-album placement. There is no
notify on the versioned device API; a successful upload is recorded settled where the platform reports it
(capability `sync-ledger`); and own-photo album placement is a write to the member's own library made when
the cycle enqueues work (capability `event-album`), not something the event can see.

No path SHALL be able to return a cycle result without passing through that decision.

This exists because five publications were previously reachable only by falling through to the end of
the cycle, so any early return silently withheld all five — and the two early returns that a device
with a backlog takes on every cycle withheld them permanently, with no error and no log line.

#### Scenario: A new cycle outcome must state what it publishes

- **WHEN** a new outcome is added to the cycle's result vocabulary and the publication decision is not
  updated
- **THEN** the build fails, because the decision is exhaustive with no fallback branch

#### Scenario: Every exit publishes

- **WHEN** a cycle ends by any route — unreadable membership, no membership, declined direction, job limit
  reached, or fully drained
- **THEN** the publication decision runs for that outcome, publishing exactly what that outcome calls
  for

#### Scenario: Publishing settles nothing

- **WHEN** a truncated or drained cycle publishes
- **THEN** it writes the device manifest and the enumeration audit line, and changes the state of no ledger
  row

