## ADDED Requirements

### Requirement: Membership transitions have no destructive verb

The upload arm SHALL be a set of **membership transitions** — join, reconfigure, permission change, leave —
plus a **launch** reconcile, expressed as one stateless, platform-free object in `:domain`'s `feature/upload`
zone (package `app.snapsync.feature.upload`). Each transition reconciles exactly two things: whether the
OS-driven extension is **registered**, and whether the app-driven engine is **armed** (its heartbeat and its
restart signal). No transition, and no mechanism verb it calls, SHALL destroy **dedup state**: none clears the
ledger and none deletes stored bytes.

The verbs the transitions call are:

- on the OS-driven mechanism: **register** (the disable → demote → enable ritual, capability
  `ios-photokit-upload`), **deregister** (the disable alone), and a read of the OS's own view of the
  registration;
- on the app-driven engine: **arm** (signal a restart to the cycle, drain, arm the first `BGProcessingTask`)
  and **disarm** (cancel in-flight transfers and the scheduled task, leaving the session intact — capability
  `ios-url-session-upload`).

None of them clears the ledger. The demote the ritual performs is a repair — `REQUESTED` → `DISCOVERED` — and
removes nothing already stored.

**The ledger is cleared at exactly two membership transitions, and never by a mechanism verb.** The upload
ledger is the **current membership's share set** (`sync-ledger`): empty while unjoined, loaded at a join,
cleared at a leave.

- a **leave** stands the mechanisms down and then clears the upload ledger (`leave-event`);
- a **provision into a new membership** — a first join, or a switch — clears the ledger and loads it from the
  device's stored-file listing before its config is saved, and a switch first **stands the previous
  membership's mechanisms down** before that load (`join-event`).

Every other transition — a **re-provision of the joined event**, a permission change, a reconfigure, a
launch — SHALL NOT clear or reset the ledger. The clear and the load are performed by the membership
use-cases over the ledger store (`LeaveEvent`, the join-time share-set load), never by a mechanism verb and
never by the transitions object, which holds no ledger. The download store is not cleared by any of them
(`download-store`).

The property being defended is **dedup**: the proof that a photo is already in the event. Losing it
re-uploads a member's whole in-window library — the failure this project exists to prevent. The stored bytes
are that proof. The ledger's `COMPLETED` rows are this membership's **local copy** of it, rebuilt at each join
from the per-device listing, which is why a leave and a join may clear them and nothing else may.

No verb needs to be destructive as a repair either: what standing a mechanism down can leave behind is
`REQUESTED` rows no transfer will settle, and those are demoted to `DISCOVERED` by whichever mechanism is next
brought up — the OS-driven ritual's demote, or the app-driven engine's restart rule (`ios-photokit-upload`,
`ios-url-session-upload`) — which the ledger's work read returns without a walk.

This replaces the two-verb `UploadProducer` seam (`start` / `stop`), the orchestrator that held one producer,
and the kind → instance table: the decisions they carried survive here, their indirection does not
(decision record: this change, `retire-the-upload-arm`).

#### Scenario: No transition destroys dedup state
- **WHEN** any transition runs — join, reconfigure, permission change, launch, or leave
- **THEN** no mechanism verb it calls clears a ledger row or deletes a stored object; any clear that follows is
  the leave's or the join-time load's own step

#### Scenario: A leave clears the upload ledger after standing the mechanisms down
- **WHEN** the user leaves the event
- **THEN** the extension is deregistered and the app engine disarmed first, and the upload ledger is then
  cleared, while the download store's rows are left untouched

#### Scenario: A switch stands down, then replaces the ledger
- **WHEN** a provision configures an event different from the joined one
- **THEN** the previous membership's mechanisms are stood down, the ledger is then cleared and loaded from the
  device's stored-file listing, and only then is the new config saved

#### Scenario: A re-provision of the joined event touches no ledger row
- **WHEN** the event that is already joined is provisioned again, or photo permission or the membership's
  direction changes, or the app launches
- **THEN** no ledger row is cleared, reset, or loaded

#### Scenario: Rows a stand-down leaves stranded are repaired by the next bring-up
- **WHEN** standing a mechanism down leaves `REQUESTED` rows that no transfer will settle, and a mechanism is
  later registered or armed
- **THEN** that bring-up demotes those rows to `DISCOVERED`, and the next cycle re-creates their uploads from
  the ledger's work read, without the walk re-deriving them

### Requirement: Membership transitions reconcile the upload mechanisms in one tested place

The decision of **what each transition does** SHALL live in the transitions object in `:domain`'s
`feature/upload` zone, not in a composition root, and SHALL be tested in `commonTest` against a fake
registration and a fake app-driven engine. It SHALL hold **no mutable state**: every decision is derived
afresh from the resolved kind ("The upload mechanism is resolved, never selected"), the membership's upload
posture, and — for the registration compare — the current photo permission.

Photo access is **usable** when it is `GRANTED` or `LIMITED`. The **desired state** SHALL be derived from the
resolved kind and the membership's **three-valued** upload posture — includes-upload / excludes-upload /
**no membership**:

| posture | resolved kind | extension registration | app engine |
| --- | --- | --- | --- |
| includes upload | OS-driven | wanted | disarmed |
| includes upload | app-driven | not wanted | armed |
| includes upload | idle (no usable access) | left as it is | disarmed |
| excludes upload, or no membership | any | not wanted | disarmed |

The transitions SHALL reach that state as follows:

| Transition | Registration | App engine |
| --- | --- | --- |
| **join** (a first join or a switch, after the join-time load; or a re-provision) | **forced**: wanted → the ritual; not wanted → deregister | armed if wanted, else disarmed |
| **reconfigure** (only when the new direction includes upload) | compared | armed if wanted, else disarmed |
| **permission change** (a real change, not a replayed value) | compared | armed if wanted, else disarmed |
| **launch** (host assembly) | compared | armed if wanted, else disarmed |
| **leave** (a leave, or a switch leaving the previous membership) | forced deregister | disarmed |

A **compared** reconcile SHALL read the OS's own view of the registration **only under a `GRANTED` grant**,
and under that grant register (through the ritual) when wanted and absent, and deregister when present and not
wanted. Under any other grant it SHALL change nothing: every registration write is refused under `LIMITED`
(`ios-photokit-upload`, "The registration cannot be changed under a partial grant"), and the OS's read is not
trustworthy under `NOT_DETERMINED`. **Every** enable, forced or compared, SHALL go through the ritual, never
a bare enable, because a bare enable over a stale record fails with `3202`.

Disarming whenever the app engine is not wanted — at launch too, not only at a change — is what cancels
app-driven work a **previous process** left running: in-flight background transfers and a submitted
`BGProcessingTask` survive process death, and their delegate would otherwise record outcomes from the app
while the extension is the writer. Disarming an engine with nothing in flight is a no-op.

A disabling reconfigure calls no transition: in-flight uploads drain and the cycle's direction gate withholds
new work (`reconfigure-membership`).

**No membership, no arm.** A permission change with no event configured SHALL arm nothing and register nothing.
Photo access can be usable while no event is configured — the join gate's photo-access explainer raises the
system dialog **before** the join is confirmed, and `join-event` requires that no upload mechanism is enabled
until the user confirms. The three-valued posture SHALL be read by the transitions object, never collapsed to
a two-valued flag in the composition root.

Registration SHALL stay **after** the join-time load (`join-event`): a first join has no registered extension
until the join transition runs, which is what prevents the load racing an extension cycle.

#### Scenario: A join under a full grant registers through the ritual
- **WHEN** an upload-inclusive event is joined while photo access is `GRANTED` on an OS carrying the OS-driven
  mechanism
- **THEN** the join transition runs the disable → demote → enable ritual and disarms the app engine

#### Scenario: A join under a limited grant arms the app engine
- **WHEN** an upload-inclusive event is joined while photo access is `LIMITED`
- **THEN** the app engine is armed and no registration write is attempted beyond the forced deregistration,
  whose refusal is tolerated

#### Scenario: A download-only join stands everything down
- **WHEN** an event is joined whose direction excludes upload
- **THEN** the extension is deregistered and the app engine disarmed, and no ledger row is touched by either

#### Scenario: Reconfiguring a download-only membership to upload registers the extension
- **WHEN** a download-only membership on an OS carrying the OS-driven mechanism, under `GRANTED`, is
  reconfigured to include upload
- **THEN** the compared reconcile finds the extension unregistered and registers it through the ritual, so the
  OS can invoke it

#### Scenario: A permission flip moves the mechanisms stand-down first
- **WHEN** photo access transitions from `LIMITED` to `GRANTED` while an upload-inclusive membership is
  configured on an OS carrying the OS-driven mechanism
- **THEN** the app engine is disarmed before the extension is registered through the ritual

#### Scenario: A grant with no event configured arms nothing
- **WHEN** photo access transitions to usable access while no event is configured
- **THEN** no registration is changed and the app engine is not armed

#### Scenario: Bringing up the extension cancels app-driven work left by an earlier process
- **WHEN** the app launches with the OS-driven kind resolved, on a device where a previous process left
  in-flight app-driven transfers or a submitted background task
- **THEN** the launch reconcile disarms the app engine, cancelling them, so only one process writes records

#### Scenario: Revocation disarms the app engine
- **WHEN** photo access transitions to `DENIED` or `NOT_DETERMINED` while an upload-inclusive membership is
  configured
- **THEN** the app engine is disarmed and the registration is left as it is

### Requirement: Launch reconciles by comparison; only a join forces the repair

The app process SHALL reconcile the upload mechanisms at **launch** through an explicit call from its
host-assembly path, and SHALL NOT rely on the permission `StateFlow`'s replayed first value to do so. The
permission subscription SHALL react to real changes only.

At launch the registration SHALL be **compared**, not forced: the ritual SHALL run only when the extension is
wanted and the OS (read under `GRANTED`) reports it absent; a deregistration only when it is present and not
wanted. The forced ritual — the repair of a stale configuration record — SHALL run at **join**.

The app engine SHALL be armed at launch when it is wanted, exactly as before, and disarmed when it is not: arming carries the restart signal
of the start-time stranded rule (`ios-url-session-upload`) and arms the first `BGProcessingTask`, and neither
duty is traded away.

This is a deliberate trade. A stale record that still reads "enabled" is repaired only at the next join; in
exchange, the extension's in-flight OS jobs survive app launches instead of being wiped and demoted on every
one. A record that reads absent is still registered at the next launch, and a failing enable is still reported
at `Error` (`ios-photokit-upload`).

A cold background launch SHALL NOT run the launch reconcile: host assembly does not run there
(`ios-app-shell`).

#### Scenario: A launch with a live registration leaves the extension's jobs alone
- **WHEN** the app launches with an upload-inclusive membership under `GRANTED` on an OS carrying the OS-driven
  mechanism, and the OS reports the extension registered
- **THEN** no registration write is made and no `REQUESTED` row is demoted

#### Scenario: A launch with a missing registration registers through the ritual
- **WHEN** the app launches under the same conditions but the OS reports the extension not registered
- **THEN** the extension is registered through the disable → demote → enable ritual

#### Scenario: A launch on the app-driven mechanism arms it
- **WHEN** the app launches with an upload-inclusive membership and the resolved kind is the app-driven one
- **THEN** a restart is signalled to the cycle and the first `BGProcessingTask` is armed

#### Scenario: The replayed permission value is not a transition
- **WHEN** the permission subscription is installed and receives the `StateFlow`'s current value
- **THEN** no transition runs for that value; only a later, different value runs the permission-change
  transition

### Requirement: Exactly one mechanism writes the ledger, enforced at each engine's entry gate

At most one process SHALL write ledger records at any time (`sync-ledger`, the single record writer). The
guarantee SHALL be **gated**: each engine's upload cycle SHALL decline at its own entry gate whenever it is not
the one that may run, and the transitions keep the OS's registration consistent with resolution.

- The **app-driven** engine's cycle SHALL run only when resolution yields the app-driven kind; otherwise it SHALL
  decline as **not resolved** and touch nothing ("The upload cycle owns its entry decision").
- The **extension**'s cycle SHALL run only under a `GRANTED` photo grant; otherwise it SHALL **withhold**.
- Whenever the app-driven kind is resolved under a `GRANTED` grant on an OS carrying the OS-driven mechanism —
  which only a development override produces — the extension SHALL be **deregistered** by the transitions,
  because the extension cannot read the override.

**This is a weakening, stated as one.** Exclusion was structural: the arm held one mechanism reference, so a
second writer had no expression. Both engines' cycles may now be constructed in one install, and what prevents
two writers is a gate decision plus the registration state. The structural form was already porous — a
background-`URLSession` relaunch reached the app engine's cycle directly, whatever mechanism was held — and the
gate closes that path too. The `:test:architecture` guard (capability `architecture-guards`) SHALL be
re-pointed at the gates and the registration state rather than retired.

The gate SHALL live in the cycle — the choke point every trigger funnels through — never at a trigger's
invoker ("The arm's direction gate lives at the choke point, never at the invoker").

#### Scenario: The app engine declines under the OS-driven mechanism
- **WHEN** any trigger drives the app engine's cycle while resolution yields the OS-driven kind
- **THEN** the cycle declines as not resolved, settles nothing, writes no ledger row, and creates no transfer

#### Scenario: The extension withholds without a full grant
- **WHEN** the OS invokes the extension while photo access is `LIMITED`, `DENIED` or `NOT_DETERMINED`
- **THEN** the extension's cycle withholds: it creates no job, walks nothing, and runs no stranded pass

#### Scenario: A pinned app-driven mechanism deregisters the extension
- **WHEN** a development override pins the app-driven kind under `GRANTED` on an OS carrying the OS-driven
  mechanism
- **THEN** the extension is deregistered, so the extension's permission-only gate cannot admit a second writer

## MODIFIED Requirements

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
upon: it holds only where a transition deregistered the extension, and a membership reconfigured to exclude
upload deliberately does not deregister it (capability `reconfigure-membership`, *A disabling change drains
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
  background task, a silent push, an arm at a membership transition, or an upload completion
- **THEN** no upload job is created, for every one of those triggers

#### Scenario: A download-only membership publishes an empty manifest
- **WHEN** a cycle runs for a membership whose direction excludes upload
- **THEN** an empty device manifest is published for that event, replacing any manifest published while the
  membership did contribute

#### Scenario: The gate holds on a tier whose invoker is the app
- **WHEN** the tier in use invokes the upload cycle from the app process rather than being invoked by the OS
- **THEN** the download-only membership still creates no upload job — the gate does not depend on which
  component invokes the cycle

#### Scenario: Standing the mechanisms down is not the gate
- **WHEN** the mechanisms have been stood down for a download-only membership and a trigger subsequently
  drives a cycle
- **THEN** no upload job is created, because the gate is read at the choke point rather than inferred from
  the mechanisms having been stood down

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
walk, upload job, device manifest, or notify. The decision SHALL have exactly five outcomes:

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
- **Not resolved** — joined, but this process's engine is not the one resolution yields (the app
  engine while the OS-driven kind is resolved). The cycle SHALL touch nothing: no settle, no ledger
  write, no jobs, no manifest.
- **Withheld** — joined, but the process may not read the library for want of a `GRANTED` grant (the
  extension under any other grant). The cycle SHALL settle narrowly ("Settling with the platform is
  owed regardless of the cycle's other outcomes") and SHALL create no job, walk nothing, and publish
  nothing.
- **Run** — joined, configured, and admitted. The cycle SHALL proceed to its contribution gate and phases.

The two admission outcomes SHALL be decided **before** the membership's selection policy is built. Building
the policy reads the denylisted-album structure, and a `PHAssetCollection` fetch under `NOT_DETERMINED`
presents iOS's permission dialog (measured: simulator, iOS 26.4, `tccd` logs `AUTHREQ_PROMPTING`); a
background wake must never raise it.

**Admission is per process and asymmetric.** The app process SHALL admit exactly when resolution yields the
app-driven kind — under `LIMITED` it runs, scoped to the selection snapshot. The extension SHALL admit exactly
under `GRANTED`, read from its own process; it SHALL NOT infer admission from its selection scope, whose
default (`Unrestricted`) is untrue under a partial grant.

A composition root SHALL NOT make this decision. A root SHALL supply only the platform reads the decision
consumes — the membership read, the device-identity probe, the build-time host, and the process's admission answer —
and the shared, tested decision function SHALL combine them. This is the same containment the `SelectionPolicy` already
has, and for the same reason: an upload tier's root is wiring-only and
untested by project rule,
so a decision placed there reaches whichever tiers its author happened to enumerate.

The **translation** of those reads into the decision's inputs SHALL itself exist exactly once, in the
shared composition (`uploadCore`, `:domain` `compose/`) — not once per root. It SHALL be **port-pure**:
one fresh three-state `ConfigReader.read()` per cycle, the identity probe, the host read, and the
admission answer, and nothing else. In particular it SHALL NOT refresh any adapter-held read-model state (such as the
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

#### Scenario: A not-resolved cycle touches nothing
- **WHEN** the app engine's cycle runs while resolution yields the OS-driven kind
- **THEN** the outcome is Not resolved, no ledger row is written, no platform outcome is drained, and no
  manifest is published

#### Scenario: An undetermined grant never builds the policy
- **WHEN** a cycle runs in either process while photo access is `NOT_DETERMINED`
- **THEN** the gate declines before the selection policy is built, so no album structure is read and no
  permission dialog is presented

#### Scenario: A limited grant admits the app and withholds the extension
- **WHEN** photo access is `LIMITED` on an OS carrying the OS-driven mechanism
- **THEN** the app engine's cycle runs scoped to the selection snapshot, and an extension cycle withholds

### Requirement: The upload mechanism is resolved, never selected

The system SHALL determine which upload mechanism may run by a **pure, exhaustively-tested resolution** from
OS facts, current photo permission, and an optional override, to a mechanism **kind**: no usable access →
idle; the OS-driven mechanism present **and** a `GRANTED` grant → OS-driven; otherwise → app-driven. The
resolved kind SHALL be read fresh wherever it is needed — by the transitions and by the app engine's entry gate
— and SHALL NOT be held.

The **transport binding** the app-driven mechanism uses is a different axis and SHALL NOT enter this
resolution: it is fixed by the compilation target (`ios-url-session-upload`, "The transport binding is
fixed by the compilation target"), and `module-architecture` requires that a fact fixed by the
compilation target is not re-derived at runtime nor admitted into this function. Which mechanism runs
stays a genuine runtime decision; which session kind it transfers over is not a decision at all.

Resolution SHALL be total, and SHALL NOT yield a kind whose mechanism this OS cannot run: the OS-driven
mechanism's registration selector does not exist below iOS 26.1, so a cell yielding it there would trap
and abort the process. The resolver — not a composition root — SHALL own this, because a root is
wiring-only and untested by project rule.

Presence and runnability are **separate facts**. "This OS has no such mechanism" and "the mechanism is present
but must not run now" SHALL NOT share an encoding. The composition SHALL construct the OS-driven registration
only where its selector exists, so a lower system cannot reach a trapping call.

The app-driven engine SHALL be constructed once per process: on every shipped binary it owns a background
`URLSession` whose identifier must stay stable and whose invalidation is terminal (`ios-url-session-upload`,
"Cancellation never invalidates the background session"). This SHALL NOT be conditioned on the transport
binding: on `iosSimulatorArm64`, where the session is a default one and its identifier is inert, a second
instance would still mean two live sessions and two task registries.

The resolved kind is also the single answer a diagnostic dump reports as its upload tier — the one value that
tells an operator which process ran and so which log to read.

#### Scenario: Resolution never yields an unrunnable mechanism
- **WHEN** every combination of OS facts, permission, and override is resolved
- **THEN** no combination yields the OS-driven kind on an OS that lacks it

#### Scenario: A limited grant resolves the app-driven kind on every OS
- **WHEN** photo access is `LIMITED`, on an OS with or without the OS-driven mechanism
- **THEN** resolution yields the app-driven kind

#### Scenario: The transport binding is not a resolution input
- **WHEN** the resolver's inputs are enumerated
- **THEN** the session kind the app-driven mechanism transfers over is not among them, and no cell varies
  by it

#### Scenario: The app engine is one instance for the process
- **WHEN** the resolved kind changes away from the app-driven mechanism and later back to it
- **THEN** the same engine is used, its session was never invalidated, and uploads resume without aborting
  the process

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

It follows too that the **extension cannot read the override**: it lives only in the app process's memory.
Establishing or clearing an override SHALL therefore run the compared registration reconcile at once
("Membership transitions reconcile the upload mechanisms in one tested place"), so a pin that resolves away
from the OS-driven mechanism under `GRANTED` deregisters the extension instead of leaving its
permission-only gate to admit a second writer.

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

#### Scenario: Pinning away from the OS-driven mechanism deregisters it
- **WHEN** an override naming the app-driven or the idle kind is established under `GRANTED` on an OS that
  carries the OS-driven mechanism, while the extension is registered
- **THEN** the extension is deregistered by that request, without waiting for another transition

### Requirement: Triggers are delivered to the mechanism and declined explicitly

App-side upload triggers SHALL be delivered to the **app-driven engine unconditionally**, whatever mechanism is
resolved — foreground entry, a silent push, a background-task heartbeat, and a photo selection change. The
caller SHALL NOT decide whether the engine is interested: the engine's cycle decides at its entry gate ("The
upload cycle owns its entry decision"), declining as **not resolved** when the OS-driven kind is resolved. The
OS-driven mechanism receives no app-side trigger — the OS schedules it — so it states no declines.

Deciding at the caller is an **invoker-gate**, and this capability has already ruled on that shape ("The arm's
direction gate lives at the choke point, never at the invoker"): the enumeration of invokers is invalidated
silently by a new tier or a new trigger.

Each trigger SHALL be a `suspend` function that returns when its work is done and SHALL NOT accept an OS
completion handler. The handler is held by the entry point that received it, for the deadline named for that OS
wake, so a declining cycle still returns and the handler is still released.

**Cold background wakes run real cycles.** A trigger reaching a process whose host was never assembled — a
`BGProcessingTask` or a silent push that launched it in the background — SHALL drive the app engine's cycle like
any other. (It previously reached an idle stand-in held until a UI-launch transition moved it, so the heartbeat's
re-submission never ran and the chain ended at the first cold wake: a customer-visible stall of background
uploads, fixed by this requirement.)

#### Scenario: A cold heartbeat wake re-submits the heartbeat
- **WHEN** a `BGProcessingTask` launches the app in the background on the app-driven mechanism, with an
  upload-inclusive membership
- **THEN** the app engine runs a cycle and the next `BGProcessingTask` is scheduled

#### Scenario: A trigger under the OS-driven mechanism declines at the gate
- **WHEN** a foreground trigger reaches the app engine while the OS-driven kind is resolved
- **THEN** the cycle declines as not resolved, schedules nothing, and the trigger completes

#### Scenario: A background wake with no usable access still completes
- **WHEN** the OS delivers a background trigger while photo access is `NOT_DETERMINED` or `DENIED`
- **THEN** no upload work is performed, no permission dialog is raised, and the OS completion handler is still
  released

### Requirement: Settling with the platform is owed regardless of the cycle's other outcomes

The upload cycle SHALL settle with the platform — drain the outcomes it is holding and adjudicate them
— on **every** cycle that reaches a usable membership **in a process that may run**, before and independently
of every later decision the cycle makes. In particular it SHALL do so when the direction gate declines.

The obligation is owed to the platform for work it has already presented, and it does not depend on
whether this membership still contributes. Measured on
iOS 26.6: with the extension still registered and jobs outstanding, a cycle that returned before the
acknowledgement pass caused the system to report `com.apple.photos.error Code=50008` ("appex failed to
acknowledge jobs for processing state"), **discard** the outstanding jobs, and record a failed attempt
against the upload-job configuration that defers the extension by ~300 seconds and escalates with the
attempt count. Expiry: re-measure at the next iOS major.

Settling creates no upload work and publishes nothing: it enumerates nothing and writes no manifest.

A cycle **withheld for permission** (the extension without a `GRANTED` grant) SHALL settle **narrowly**: it
SHALL drain the terminal jobs the platform presented and adjudicate their failures — discharging the
acknowledgement obligation — but SHALL re-create no retry, create no job, and run no stranded pass. A retry or a
stranded demotion there would write the ledger while another process may be its writer.

A cycle that is **not resolved** in its process (the app engine while the OS-driven kind is resolved) SHALL
settle **nothing**. Its transport holds no transfer for the rows the other process requested, so its stranded
pass would demote them — a second ledger writer.

#### Scenario: A declined direction still settles
- **WHEN** the membership's direction excludes upload
- **THEN** the cycle still settles with the platform

#### Scenario: An unusable membership settles nothing
- **WHEN** the entry gate reports the membership unreadable, or definitively absent
- **THEN** the cycle settles with no platform at all, because settling requires the configuration the
  gate could not supply

#### Scenario: A withheld cycle acknowledges without creating work
- **WHEN** the extension is invoked without a `GRANTED` grant while the OS presents terminal jobs
- **THEN** every presented job is acknowledged and its outcome recorded, and no retry is re-created, no job is
  created, and no `REQUESTED` row is demoted by a stranded pass

#### Scenario: A not-resolved cycle settles nothing
- **WHEN** the app engine's cycle runs while the OS-driven kind is resolved and the ledger holds `REQUESTED`
  rows the extension created
- **THEN** no terminal job is drained, no retry is created, and every one of those rows is left `REQUESTED`

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

- **WHEN** a cycle ends by any route — unreadable membership, no membership, not resolved, withheld for
  permission, declined direction, job limit reached, or fully drained
- **THEN** the publication decision runs for that outcome, publishing exactly what that outcome calls
  for

#### Scenario: Publishing settles nothing

- **WHEN** a truncated or drained cycle publishes
- **THEN** it writes the device manifest and the enumeration audit line, and changes the state of no ledger
  row

#### Scenario: A temporary permission state publishes nothing
- **WHEN** a cycle ends not resolved or withheld for permission
- **THEN** no device manifest is written — not even an empty one — so a grant flip never removes this device's
  photos from the event union

## REMOVED Requirements

### Requirement: Upload producer seam has no destructive verb
**Reason**: The `UploadProducer` seam (`start` / `stop`) is deleted with the arm that held it.
**Migration**: Its guarantees — no verb clears the ledger; only a leave and a join-time load replace it; stranded
rows are repaired by the next bring-up — move to "Membership transitions have no destructive verb".

### Requirement: Lifecycle orchestration is tier-neutral and tested
**Reason**: There is no orchestrator holding a producer; the transitions derive their action afresh from the
resolved kind and the membership posture.
**Migration**: The transition table, the three-valued posture and "no membership, no arm" move to "Membership
transitions reconcile the upload mechanisms in one tested place", which adds the reconfigure transition and the
launch reconcile.

### Requirement: Exactly one producer started per process
**Reason**: Exclusion is no longer structural — no single held reference exists to make two unstartable.
**Migration**: "Exactly one mechanism writes the ledger, enforced at each engine's entry gate".

### Requirement: A mechanism is always resolved
**Reason**: There is no held mechanism and no idle stand-in: triggers go to the app-driven engine, whose entry
gate declines.
**Migration**: The background-wake guarantee (no upload work without usable access, the OS handler still
released) is stated in "Triggers are delivered to the mechanism and declined explicitly".
