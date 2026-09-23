# upload-lifecycle Specification

## Purpose

The **upload arm**: what each membership transition — join, re-provision of the joined event, reconfigure,
permission change, launch, leave — does to the two uploaders, which transitions replace the upload ledger, and
who may create upload jobs. The OS-driven uploader is an extension **registration** (register through the
disable → enable toggle, or deregister — at a leave only); the app-driven uploader is an **engine** (arm or
disarm its heartbeat; cancel its transfers at a leave only). This capability owns the decision, in one tested, stateless, platform-free
place (`UploadTransitions`), and owns the upload cycle's entry decision that every trigger funnels through.

It exists because the upload lifecycle previously had **no owner**. It was smeared across the two tier
specs and the iOS composition root — a file in `:app:ios`, which the project's own hard rule declares
wiring-only and untested (capability `testing-architecture`) — so no contract described it and no test could
reach it. When a second upload tier arrived, the app-driven tier (iOS 18–26.0) inherited a PhotoKit-shaped
"disable→enable" ritual on every provision whose *disable* half resolved to a full leave while its *enable*
half was a no-op below iOS 26.1: joining an event tore the arm down, started nothing, and re-uploaded the
user's whole post-cutoff library on the tier every current user ran. The fix was structural and stays so: no
verb a transition calls can wipe anything. What replaces the upload ledger is a membership decision made by
the membership use-cases alone: the ledger is the current membership's share set (`sync-ledger`), so a leave
clears it and a first join or a switch loads it from the device's stored-file listing
(`upload-state-reconciliation`, `changes/archive/2026-09-21-join-loads-leave-clears`).

**Both uploaders run; nothing hands off.** An earlier design held one mechanism instance behind a kind →
instance table, and its successor gated "exactly one writer" at each engine's entry. Both made every hand-off
between the uploaders cancel or orphan in-flight work, and every orphan needed a repair — three of them for one
fact. The premise was dropped (`changes/archive/2026-09-22-both-uploaders-active`): the app's uploader creates under any usable grant on every OS, the
extension under a full grant from iOS 26.1, each deciding at its own entry gate; a cycle picks only
`DISCOVERED` rows and records `REQUESTED` only after its job exists, so an overlap is a duplicate upload of the
same object, never a loss. The extension's registration spans the membership, and no transition but a leave
stops in-flight work — so nothing is orphaned, and nothing repairs. What is left of the old resolver is one
pure fact, whether the extension may be registered (`model/extensionRegistrable`). The `:test:architecture`
guard drives the transitions to hold that.

The transitions are written against the **current single-active-membership contract** (capability
`join-event`): no membership means no arm. Concurrent multi-event membership is a named future direction; a
ledger scoped to the current membership deepens the single-membership assumption, and new work SHALL NOT
deepen it further.

Decision records: `changes/archive/2026-07-12-fix-app-driven-upload-lifecycle` (the owner and the
non-destructive verbs), `changes/archive/2026-08-27-fix-cap-truncation-loop` (settling is owed; publication
is decided by outcome), `changes/archive/2026-09-21-retire-the-upload-arm` (the transitions, the admission outcomes,
launch-compares-join-forces), `changes/archive/2026-09-22-both-uploaders-active` (both uploaders active, registration spanning the
membership, no repairs, create-until-refused).
## Requirements
### Requirement: The arm's direction gate lives at the choke point, never at the invoker

An upload arm's participation-direction gate SHALL live at the **choke point** — the one function every
trigger, on every tier, funnels through (`UploadCycle.run()`) — and SHALL NOT be placed at the arm's
**invoker**. No upload job SHALL be created for a membership whose direction excludes upload, at **any**
trigger and on **any** tier.

**The gate is the selection policy admitting nothing.** A membership whose direction excludes upload SHALL
yield a selection policy that contributes nothing (`policy.contributes` false), and the cycle SHALL decline on
that answer. No separate direction read SHALL exist in either uploader or in the membership transitions: the
extension stays registered for a download-only membership, the app engine is armed for one, and both cycles
decline on the policy. A second read of the direction is a second place for the answer to drift. Decision
record: `changes/both-uploaders-active`.

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

What SHALL remain behind the gate: upload job creation, the retry pass, and the discovery walk. The walk
staying behind it is load-bearing: a deny-all policy SHALL never count as an authoritative walk, which would
delete rows.

For a **contributing** membership the acknowledgement pass SHALL keep its existing position, behind the
direction gate and after the retry pass. The declined cycle runs it before returning: the drained jobs it
settles were created under the same event by the same membership, whose direction — not whose identity —
changed.

Placing the acknowledgement behind the gate was justified by the premise that a non-contributing
membership's extension has been deregistered, so the OS presents nothing. That premise SHALL NOT be relied
upon, and it is now false by design: the extension stays registered for the whole membership, a download-only
one included, and is deregistered only at a leave ("Membership transitions reconcile the upload mechanisms in
one tested place"). Measured on iOS 26.6: with the extension still registered and jobs outstanding, a
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

#### Scenario: A registered extension is not a contribution
- **WHEN** the extension is registered and the app engine armed for a download-only membership — joined that
  way, or reconfigured away from upload — and a trigger drives a cycle in either process
- **THEN** no upload job is created, because the gate is the policy read at the choke point rather than
  inferred from which uploaders are registered or armed

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
walk, upload job, device manifest, or notify. The decision SHALL have exactly four outcomes:

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
- **Withheld** — joined, but this process may not create: the extension under any grant other than
  `GRANTED`; the app under any grant other than `GRANTED` or `LIMITED`, under `LIMITED` while the selection
  has not been read yet (capability `limited-photo-access`), or while the control channel has switched its
  creation off. The cycle SHALL settle narrowly ("Settling with the platform is owed regardless
  of the cycle's other outcomes") and SHALL create no job, walk nothing, and publish nothing.
- **Run** — joined, configured, and admitted. The cycle SHALL proceed to its contribution gate and phases.

There is no "not resolved" outcome: no process declines because the other uploader may run ("Both uploaders
may run; an overlap is a duplicate, never a loss"). The app's former not-resolved case — no usable access —
is **Withheld**, whose narrow settle is safe in the app too: it creates nothing, and there is no stranded pass
left to run.

The two admission outcomes SHALL be decided **before** the membership's selection policy is built. Building
the policy reads the denylisted-album structure, and a `PHAssetCollection` fetch under `NOT_DETERMINED`
presents iOS's permission dialog (measured: simulator, iOS 26.4, `tccd` logs `AUTHREQ_PROMPTING`); a
background wake must never raise it.

**Admission is per process and asymmetric.** The app process SHALL admit exactly under a usable grant —
`GRANTED` or `LIMITED` — unless the control channel has switched its creation off: under `LIMITED` it runs
scoped to the selection snapshot, and under `GRANTED` it runs alongside a registered extension. Under
`LIMITED` it SHALL admit only once the selection has been read, and SHALL withhold while the selection
scope is `Unread`. An unread selection is not an empty one: a cycle run over it would delete the rows of
every photo (decision record `changes/selection-is-the-walk`, D1). The admission SHALL read the same
snapshot cell the selection scope is derived from, so the two cannot disagree. It SHALL NOT
consult the registration state. The extension SHALL admit exactly under `GRANTED`, read from its own process;
it SHALL NOT infer admission from its selection scope, whose default (`Unrestricted`) is untrue under a
partial grant. Decision record: `changes/both-uploaders-active` (D3).

A composition root SHALL NOT make this decision. A root SHALL supply only the platform reads the decision
consumes — the membership read, the device-identity probe, the build-time host, and the process's admission answer —
and the shared, tested decision function SHALL combine them. This is the same containment the `SelectionPolicy` already
has, and for the same reason: an upload tier's root is wiring-only and
untested by project rule,
so a decision placed there reaches whichever tiers its author happened to enumerate.

The **translation** of those reads into the decision's inputs SHALL itself exist exactly once, in the
shared composition (`uploadCore`, `:domain` `compose/`) — not once per root. It SHALL be **port-pure**:
one read of the ledger's manifest version (capability `sync-ledger`), then one fresh three-state
`ConfigReader.read()` per cycle, the identity probe, the host read, and the admission answer, and nothing
else. The manifest version SHALL be read **first** — before the config, and therefore before the policy and
the ledger rows the manifest is projected from — and SHALL be carried with the cycle to the manifest
producer (capability `device-manifest`). The order is the ordering argument: every change that could alter
the projection advances the version, so a change the projection misses happened after the read and carries
a higher version. A version that cannot be read SHALL produce **Skip**, like any other unreadable input. In particular it SHALL NOT refresh any adapter-held read-model state (such as the
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

#### Scenario: The manifest version is read before the membership

- **WHEN** a cycle is gated
- **THEN** the ledger's manifest version is read before the config, and the manifest the cycle publishes
  carries that version

#### Scenario: The entry-gate translation is one implementation
- **WHEN** any tier (or the world harness) assembles an upload cycle
- **THEN** its entry gate is the shared `uploadCore` translation over that tier's ports — a fresh
  three-state read per cycle with no adapter read-model refresh — so no tier can carry gate semantics
  another tier lacks

#### Scenario: The app without usable access withholds
- **WHEN** the app engine's cycle runs while photo access is `DENIED` or `NOT_DETERMINED`, or while the control
  channel has switched the app's creation off
- **THEN** the outcome is Withheld: the cycle settles narrowly, creates no job, walks nothing, and publishes no
  manifest

#### Scenario: An undetermined grant never builds the policy
- **WHEN** a cycle runs in either process while photo access is `NOT_DETERMINED`
- **THEN** the gate declines before the selection policy is built, so no album structure is read and no
  permission dialog is presented

#### Scenario: A limited grant admits the app and withholds the extension
- **WHEN** photo access is `LIMITED` on an OS carrying the OS-driven mechanism
- **THEN** the app engine's cycle runs scoped to the selection snapshot once the selection has been read,
  and an extension cycle withholds

#### Scenario: An unread selection withholds the app
- **WHEN** photo access is `LIMITED` and the app's cycle runs before the first selection read
- **THEN** the outcome is Withheld: the cycle settles narrowly, reads nothing, creates no job, deletes no row,
  and publishes no manifest

#### Scenario: A full grant admits both processes
- **WHEN** photo access is `GRANTED` on an OS carrying the OS-driven mechanism
- **THEN** both the app engine's cycle and an extension cycle are admitted, whether or not the extension is
  registered

### Requirement: Every selection and side-effect port is answered at the call site

The upload cycle SHALL require each port that shapes what a member contributes or what a completed cycle
emits — the device-manifest hook, the echo-suppression source, the denylisted-album source, the
completion-notify hook, and the membership read (whose selection policy carries the participation direction —
a download-only membership is a policy that admits nothing). None SHALL carry a default. (The cycle
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

### Requirement: A mechanism override is a runtime input a shipped build cannot carry

The control channel SHALL be able to switch **each uploader** on or off for testing — the app's job creation,
and the extension's registration — through an optional **override** read fresh at every use rather than
captured once. Its absence SHALL mean both uploaders on, and the app's admission and the registration fact SHALL
remain total without it — which is the production case on every shipped build.

The override SHALL address each uploader separately rather than name one mechanism. Both uploaders may run
("Both uploaders may run; an overlap is a duplicate, never a loss"), so the states worth reproducing are each
uploader alone and both together, which a single named kind cannot express. Switching the app **off** SHALL make
the app's admission answer **Withheld** — it still records the completions its transfers report, and creates
nothing. Switching the extension **off** SHALL make the registration fact false ("Whether the extension may be
registered is one pure fact"). Decision record: `changes/both-uploaders-active` (D8).

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
Establishing or clearing an override SHALL therefore run the registration reconcile at once ("Membership
transitions reconcile the upload mechanisms in one tested place"): switching the extension off SHALL deregister
it at once, since its permission-only gate would otherwise keep creating — accepted: that wipes its in-flight OS
jobs, which is the test's intent, on a path only a control-channel build has — and switching it back on
registers it where the fact allows.

#### Scenario: A shipped build cannot be overridden

- **WHEN** a build made without the control channel runs on any OS under any permission
- **THEN** no override is in effect, because nothing in that binary is able to establish one

#### Scenario: Either uploader can run alone

- **WHEN** the override switches the app off and leaves the extension on, under `GRANTED` on an OS that carries
  the OS-driven mechanism
- **THEN** only the extension creates jobs, and the app's cycles withhold while still recording the
  completions of transfers they created earlier

#### Scenario: Absence of an override is not a failure

- **WHEN** no override has been established
- **THEN** the app's admission and the registration fact proceed from OS facts and permission alone, and
  answer for every input combination

#### Scenario: Switching the extension off deregisters it
- **WHEN** the override switches the extension off under `GRANTED` on an OS that carries the OS-driven
  mechanism, while the extension is registered
- **THEN** the extension is deregistered by that request, without waiting for another transition

### Requirement: Triggers are delivered to the mechanism and declined explicitly

App-side upload triggers SHALL be delivered to the **app-driven engine unconditionally**, whatever the
registration state and the photo permission — foreground entry, a silent push, a background-task heartbeat, and
a photo selection change. The caller SHALL NOT decide whether the engine is interested: the engine's cycle
decides at its entry gate ("The upload cycle owns its entry decision"), withholding when the app may not create.
The OS-driven mechanism receives no app-side trigger — the OS schedules it — so it states no declines.

Deciding at the caller is an **invoker-gate**, and this capability has already ruled on that shape ("The arm's
direction gate lives at the choke point, never at the invoker"): the enumeration of invokers is invalidated
silently by a new tier or a new trigger.

**An upload completion always records, and drives a cycle only when the app may create.** A transfer's
completion SHALL be recorded through the guarded write whatever the app's admission. Whether that completion
then drives an app cycle SHALL be decided by the tested pump from the app's current admission — a cycle only on
**Admit** — and never by the shell that receives the callback. Late completions after a revoke or a leave
(`-999`s arriving when their transfers are cancelled or finish) otherwise keep driving cycles that can do
nothing (field observation, 2026-09-16). This is the same admission the cycle's entry gate reads, applied before
a cycle is started rather than instead of the gate. Decision record: `changes/both-uploaders-active` (D7).

Each trigger SHALL be a `suspend` function that returns when its work is done and SHALL NOT accept an OS
completion handler. The handler is held by the entry point that received it, for the deadline named for that OS
wake, so a declining cycle still returns and the handler is still released.

**Cold background wakes run real cycles.** A trigger reaching a process whose host was never assembled — a
`BGProcessingTask` or a silent push that launched it in the background — SHALL drive the app engine's cycle like
any other. (It previously reached an idle stand-in held until a UI-launch transition moved it, so the heartbeat's
re-submission never ran and the chain ended at the first cold wake: a customer-visible stall of background
uploads, fixed by this requirement.)

#### Scenario: A cold heartbeat wake re-submits the heartbeat
- **WHEN** a `BGProcessingTask` launches the app in the background, with an upload-inclusive membership and a
  usable photo grant
- **THEN** the app engine runs a cycle and the next `BGProcessingTask` is scheduled

#### Scenario: A trigger while the extension is registered runs the app's cycle
- **WHEN** a foreground trigger reaches the app engine under `GRANTED` while the extension is registered
- **THEN** the app's cycle is admitted and creates jobs only for `DISCOVERED` rows, leaving every row the
  extension requested untouched

#### Scenario: A background wake with no usable access still completes
- **WHEN** the OS delivers a background trigger while photo access is `NOT_DETERMINED` or `DENIED`
- **THEN** no upload work is performed, no permission dialog is raised, and the OS completion handler is still
  released

#### Scenario: A late completion after a revoke records and drives nothing
- **WHEN** an app transfer completes after photo access was revoked, or after a leave
- **THEN** its outcome is recorded through the guarded write, and no app cycle is started for it

### Requirement: Settling with the platform is owed regardless of the cycle's other outcomes

The upload cycle SHALL settle with the platform — drain the outcomes it is holding and adjudicate them
— on **every** cycle that reaches a usable membership, in either process and under either admission outcome,
before and independently of every later decision the cycle makes. In particular it SHALL do so when the
direction gate declines.

The obligation is owed to the platform for work it has already presented, and it does not depend on
whether this membership still contributes. Measured on
iOS 26.6: with the extension still registered and jobs outstanding, a cycle that returned before the
acknowledgement pass caused the system to report `com.apple.photos.error Code=50008` ("appex failed to
acknowledge jobs for processing state"), **discard** the outstanding jobs, and record a failed attempt
against the upload-job configuration that defers the extension by ~300 seconds and escalates with the
attempt count. Expiry: re-measure at the next iOS major.

Settling creates no upload work and publishes nothing: it enumerates nothing and writes no manifest.

A **withheld** cycle — in either process ("The upload cycle owns its entry decision") — SHALL settle
**narrowly**: it SHALL drain the terminal jobs the platform presented and adjudicate their failures —
discharging the acknowledgement obligation and recording every outcome through the guarded write — but SHALL
re-create no retry and create no job. A retry re-creation is a job creation, and a withheld process may not
create. There is no stranded pass to withhold: nothing orphans a `REQUESTED` row ("Both uploaders may run; an
overlap is a duplicate, never a loss").

#### Scenario: A declined direction still settles
- **WHEN** the membership's direction excludes upload
- **THEN** the cycle still settles with the platform

#### Scenario: An unusable membership settles nothing
- **WHEN** the entry gate reports the membership unreadable, or definitively absent
- **THEN** the cycle settles with no platform at all, because settling requires the configuration the
  gate could not supply

#### Scenario: A withheld cycle acknowledges without creating work
- **WHEN** the extension is invoked without a `GRANTED` grant while the OS presents terminal jobs
- **THEN** every presented job is acknowledged and its outcome recorded, and no retry is re-created and no
  job is created

#### Scenario: A withheld app cycle leaves the extension's rows alone
- **WHEN** the app engine's cycle is withheld and the ledger holds `REQUESTED` rows the extension created
- **THEN** no retry is created, no job is created, and every one of those rows is left `REQUESTED` for the
  extension's completions to settle

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

- **WHEN** a cycle ends by any route — unreadable membership, no membership, withheld for permission,
  declined direction, job limit reached, or fully drained
- **THEN** the publication decision runs for that outcome, publishing exactly what that outcome calls
  for

#### Scenario: Publishing settles nothing

- **WHEN** a truncated or drained cycle publishes
- **THEN** it writes the device manifest and the enumeration audit line, and changes the state of no ledger
  row

#### Scenario: A temporary permission state publishes nothing
- **WHEN** a cycle ends withheld for permission, in either process
- **THEN** no device manifest is written — not even an empty one — so a grant flip never removes this device's
  photos from the event union

### Requirement: Membership transitions have no destructive verb

The upload arm SHALL be a set of **membership transitions** — join, re-provision of the joined event,
reconfigure, permission change, leave — plus a **launch** reconcile, expressed as one stateless,
platform-free object in `:domain`'s `feature/upload` zone (package `app.snapsync.feature.upload`). Each
transition reconciles exactly two things: whether the OS-driven extension is **registered**, and whether the
app-driven engine is **armed** (its heartbeat); a leave additionally cancels the app engine's transfers. No
transition, and no mechanism verb it calls, SHALL destroy **dedup state**: none clears the ledger and none
deletes stored bytes.

The verbs the transitions call are:

- on the OS-driven mechanism: **register** (the disable → enable toggle, capability `ios-photokit-upload`),
  **deregister** (the disable alone), and a read of the OS's own view of the registration;
- on the app-driven engine: **arm** (drain, and arm the first `BGProcessingTask`), **disarm** (cancel the
  scheduled task — the heartbeat — and nothing else: in-flight transfers finish and record), and **cancel
  transfers** (cancel the in-flight background transfers and delete their staged files, leaving the session
  intact — capability `ios-url-session-upload`), which only a leave calls.

None of them writes the ledger itself. A cancelled transfer's completion is recorded by the transport's guarded
write like any other completion, and the registration toggle performs no demote.

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

**Nothing needs a repair, because no transition but a leave stops in-flight work.** A deregistration — which
wipes the extension's OS jobs — and a transfer cancel happen only at a leave, whose clear removes those rows
anyway, and at a switch's leave, which precedes the load that replaces them. A revoke, a narrowing, a
reconfigure and a re-provision stop no in-flight work: a disarm stops only new wakes, admission stops only new
creation, and in-flight transfers finish and record. So no transition leaves a `REQUESTED` row that no transfer
will settle, and there is no demote, no stranded rule and no restart signal to repair one. (Formerly a
stand-down's stranded rows were demoted to `DISCOVERED` by whichever mechanism was next brought up; two of the
orphaning paths — a download-only deregistration, and an app disarm whose `-999` never landed — could be
covered by nothing but the ritual's bulk demote, which is why the stand-downs were removed rather than the
repairs narrowed. Decision record: `changes/both-uploaders-active`.)

This replaces the two-verb `UploadProducer` seam (`start` / `stop`), the orchestrator that held one producer,
and the kind → instance table: the decisions they carried survive here, their indirection does not
(decision record: `changes/archive/2026-09-21-retire-the-upload-arm`).

#### Scenario: No transition destroys dedup state
- **WHEN** any transition runs — join, re-provision, reconfigure, permission change, launch, or leave
- **THEN** no mechanism verb it calls clears a ledger row or deletes a stored object; any clear that follows is
  the leave's or the join-time load's own step

#### Scenario: A leave clears the upload ledger after standing the mechanisms down
- **WHEN** the user leaves the event
- **THEN** the extension is deregistered, the app engine disarmed and its transfers cancelled first, and the
  upload ledger is then cleared, while the download store's rows are left untouched

#### Scenario: A switch stands down, then replaces the ledger
- **WHEN** a provision configures an event different from the joined one
- **THEN** the previous membership's mechanisms are stood down, the ledger is then cleared and loaded from the
  device's stored-file listing, and only then is the new config saved

#### Scenario: A re-provision of the joined event touches no ledger row
- **WHEN** the event that is already joined is provisioned again, or photo permission or the membership's
  direction changes, or the app launches
- **THEN** no ledger row is cleared, reset, or loaded

#### Scenario: No transition but a leave stops in-flight work
- **WHEN** any transition other than a leave runs — a join into a first membership, a re-provision, a
  reconfigure in either direction, a permission change in any direction, or a launch
- **THEN** no in-flight app transfer is cancelled, no registration is removed, and no `REQUESTED` row is
  rewritten, so no row is left that no transfer will settle

### Requirement: Membership transitions reconcile the upload mechanisms in one tested place

The decision of **what each transition does** SHALL live in the transitions object in `:domain`'s
`feature/upload` zone, not in a composition root, and SHALL be tested in `commonTest` against a fake
registration and a fake app-driven engine. It SHALL hold **no mutable state**: every decision is derived
afresh from whether a membership exists, the current photo permission, the registration fact ("Whether the
extension may be registered is one pure fact").

The membership's **direction SHALL NOT be an input**. A download-only membership is the selection policy
admitting nothing, which each cycle reads at its entry ("The arm's direction gate lives at the choke point,
never at the invoker"); a transition that read the direction too would be a second gate, and standing the
mechanisms down for it is what orphaned rows. Photo access is **usable** when it is `GRANTED` or `LIMITED`.

The transitions SHALL act as follows:

| Transition | Registration | App engine |
| --- | --- | --- |
| **join** (a first join or a switch, after the join-time load) | **forced** toggle where registrable; nothing otherwise | armed if access is usable |
| **re-provision of the joined event** (`SwitchDecision.Stay`) | nothing — no read and no write | nothing |
| **reconfigure** (either direction) | nothing | armed if access is usable |
| **permission change** (a real change, not a replayed value) | **compared**, register only | armed if access is usable, else disarmed |
| **launch** (host assembly) | **compared**, register only | armed if access is usable, else disarmed |
| **override change** (control-channel builds only) | deregister if the extension is switched off; else compared, register only | armed if access is usable, else disarmed |
| **leave** (a leave, or a switch leaving the previous membership) | forced deregister | disarmed, and its transfers cancelled |

Why each cell:

- **Registration spans the membership.** The extension is registered from the join to the leave wherever the
  registration fact allows, download-only memberships included: their extension cycles decline on the policy,
  settle, and return `SKIPPED`, so a registration costs an OS launch and nothing else, and a later reconfigure
  to upload needs no registration step at all.
- **Never deregister on a permission change.** Under `LIMITED` every registration write is refused (`3311`,
  `ios-photokit-upload`, "The registration cannot be changed under a partial grant"), and a registration that
  survived a downgrade is still invoked by the OS (measured SE2/26.6, 2026-09-21); its cycle withholds and
  records. Under `NOT_DETERMINED` or `DENIED` the extension withholds too. A deregistration gains nothing and
  wipes jobs.
- **A compared reconcile registers only.** It SHALL read the OS's own view of the registration **only when the
  extension is registrable** (so only under `GRANTED`: the read is not trustworthy under `NOT_DETERMINED`), and
  register when the OS reads it absent. It SHALL never deregister. It registers only where the OS reads **no**
  record, so there are no jobs to wipe.
- **`Stay` does nothing.** A re-scan of the joined event changes nothing about the membership, the share-set
  load is already skipped there, and the stale-record repair does not need it: a reinstall wipes the config (the
  App Group goes with the app), so a reinstalled device always arrives as a real join. The join transition
  SHALL be reached only through the membership entry (leave the previous membership, load, save, start
  uploads — one ordered feature rule), which the provision runs on a real entry and never on `Stay`; the
  provision's `Stay` branch only saves the config, so the flow keeps one call per branch.
- **A switch** calls the leave first (deregister, disarm, cancel transfers), then the load replaces the ledger,
  then the join registers — so the join's toggle meets no live job of the new membership. This is the one place
  a membership change still cancels work, because a switch *is* a leave, and the rows are replaced anyway.
- **Arming is a kick; the cycle decides.** Arming under a usable grant starts a cycle whose own entry decides
  what it may do — the policy for a download-only membership, the control channel's switch for the app.

**Every** enable, forced or compared, SHALL go through the disable → enable toggle, never a bare enable,
because a bare enable over a stale record fails with `3202`.

**Disarm stops new wakes, not transfers.** Disarming SHALL cancel only the scheduled `BGProcessingTask`;
in-flight transfers finish and record through the guarded write. Only a leave cancels the app's transfers
(deleting their staged files). Revocation therefore stops new creation (admission) and new wakes (the
heartbeat), and lets work already in flight complete. Decision record: `changes/both-uploaders-active` (D5, D6).

**No membership, no arm.** A permission change or a launch with no event configured SHALL arm nothing and
register nothing; a surviving record it finds is left as it is, because only a leave deregisters. Photo access
can be usable while no event is configured — the join gate's photo-access explainer raises the system dialog
**before** the join is confirmed, and `join-event` requires that no upload mechanism is enabled until the user
confirms. Whether a membership exists SHALL be read by the transitions object, never decided in the composition
root.

Registration SHALL stay **after** the join-time load (`join-event`): a first join has no registered extension
until the join transition runs, which is what prevents the load racing an extension cycle.

#### Scenario: A join under a full grant registers through the toggle
- **WHEN** an event is joined while photo access is `GRANTED` on an OS carrying the OS-driven mechanism
- **THEN** the join transition runs the disable → enable toggle, rewrites no `REQUESTED` row, and arms the app
  engine

#### Scenario: A join under a limited grant arms the app engine
- **WHEN** an event is joined while photo access is `LIMITED`
- **THEN** the app engine is armed and no registration write is attempted

#### Scenario: A download-only join registers like any join
- **WHEN** an event whose direction excludes upload is joined while photo access is `GRANTED` on an OS carrying
  the OS-driven mechanism
- **THEN** the extension is registered and the app engine armed, and every cycle either process runs for the
  membership declines on the selection policy

#### Scenario: A re-provision of the joined event does nothing to uploads
- **WHEN** the joined event is provisioned again (`SwitchDecision.Stay`)
- **THEN** no registration call is made — no read, no write — the app engine is neither armed nor disarmed, and
  the extension's in-flight OS jobs survive

#### Scenario: Reconfiguring never touches the registration
- **WHEN** a membership is reconfigured, to or away from upload
- **THEN** no registration call is made, and the app engine is armed if photo access is usable

#### Scenario: A permission upgrade registers a missing record
- **WHEN** photo access transitions from `LIMITED` to `GRANTED` with a membership configured on an OS carrying
  the OS-driven mechanism, and the OS reads the extension not registered
- **THEN** the extension is registered through the toggle, and the app engine stays armed

#### Scenario: A permission downgrade cancels nothing
- **WHEN** photo access transitions from `GRANTED` to `LIMITED` while the extension is registered and app
  transfers are in flight
- **THEN** no registration write is attempted, no transfer is cancelled, and the app engine stays armed

#### Scenario: A grant with no event configured arms nothing
- **WHEN** photo access transitions to usable access while no event is configured
- **THEN** the extension is not registered and the app engine is not armed

#### Scenario: Revocation stops new work only
- **WHEN** photo access transitions to `DENIED` or `NOT_DETERMINED` while a membership is configured
- **THEN** the app engine is disarmed — its heartbeat cancelled — no in-flight transfer is cancelled, and the
  registration is left as it is

### Requirement: Launch reconciles by comparison; only a join forces the repair

The app process SHALL reconcile the upload mechanisms at **launch** through an explicit call from its
host-assembly path, and SHALL NOT rely on the permission `StateFlow`'s replayed first value to do so. The
permission subscription SHALL react to real changes only.

At launch the registration SHALL be **compared**, not forced: the toggle SHALL run only when the extension is
registrable and the OS (read under `GRANTED`) reports it absent. A launch SHALL NOT deregister. The forced
toggle — the repair of a stale configuration record — SHALL run at **join**.

The app engine SHALL be armed at launch when photo access is usable, and disarmed when it is not: arming arms
the first `BGProcessingTask`, so a launch always re-establishes the heartbeat chain.

This is a deliberate trade. A stale record that still reads "enabled" is repaired only at the next join; in
exchange, the extension's in-flight OS jobs survive app launches instead of being wiped on every one. A record
that reads absent is still registered at the next launch, and a failing enable is still reported at `Error`
(`ios-photokit-upload`).

A cold background launch SHALL NOT run the launch reconcile: host assembly does not run there
(`ios-app-shell`).

#### Scenario: A launch with a live registration leaves the extension's jobs alone
- **WHEN** the app launches with a membership under `GRANTED` on an OS carrying the OS-driven mechanism, and
  the OS reports the extension registered
- **THEN** no registration write is made and no `REQUESTED` row is rewritten

#### Scenario: A launch with a missing registration registers through the toggle
- **WHEN** the app launches under the same conditions but the OS reports the extension not registered
- **THEN** the extension is registered through the disable → enable toggle

#### Scenario: A launch under a usable grant arms the app engine
- **WHEN** the app launches with a membership and photo access `GRANTED` or `LIMITED`
- **THEN** the first `BGProcessingTask` is armed, whatever the registration state

#### Scenario: A launch never deregisters
- **WHEN** the app launches with a registered extension under a grant that does not make it registrable
- **THEN** no deregistration is attempted, and the extension's cycles withhold at their own gate

#### Scenario: The replayed permission value is not a transition
- **WHEN** the permission subscription is installed and receives the `StateFlow`'s current value
- **THEN** no transition runs for that value; only a later, different value runs the permission-change
  transition

### Requirement: Whether the extension may be registered is one pure fact

The system SHALL decide whether the OS-driven extension may be registered by one **pure, exhaustively-tested,
total** function of the OS fact and the current photo permission (`extensionRegistrable`): true exactly when
this OS carries the OS-driven mechanism (iOS ≥ 26.1) **and** photo access is `GRANTED` — and, in a build
carrying the control channel, the extension is not switched off ("A mechanism override is a runtime input a
shipped build cannot carry"). The fact SHALL be read fresh wherever it is needed — by the transitions and by
the diagnostic dump — and SHALL NOT be held.

It answers "may the extension be registered", not "which single uploader runs". Both uploaders may run ("Both
uploaders may run; an overlap is a duplicate, never a loss"), and whether a process **creates** is decided by
its own admission at its cycle's entry ("The upload cycle owns its entry decision"), not by this fact. It
replaces the mechanism resolution (`resolveUploadMechanism` / `UploadMechanism`), whose "which one" answer no
longer exists. That resolution's idle kind existed so an OS trigger was still answered; every trigger is now a
`suspend` function whose OS handler its entry point holds ("Triggers are delivered to the mechanism and
declined explicitly"), so nothing routes to "no mechanism" any more.

The fact SHALL NEVER be true on an OS below iOS 26.1: the registration selector does not exist there, so a
true answer would lead to a call that traps and aborts the process. The function — not a composition root —
SHALL own this, because a root is wiring-only and untested by project rule.

Presence and runnability are **separate facts**. "This OS has no such mechanism" and "the mechanism is present
but may not be registered now" SHALL NOT share an encoding. The composition SHALL construct the OS-driven
registration only where its selector exists, so a lower system cannot reach a trapping call.

The **transport binding** the app-driven engine uses is a different axis and SHALL NOT enter this function: it
is fixed by the compilation target (`ios-url-session-upload`, "The transport binding is fixed by the
compilation target"), and `module-architecture` requires that a fact fixed by the compilation target is not
re-derived at runtime nor admitted into such a function.

The app-driven engine SHALL be constructed once per process: on every shipped binary it owns a background
`URLSession` whose identifier must stay stable and whose invalidation is terminal (`ios-url-session-upload`,
"Cancellation never invalidates the background session"). This SHALL NOT be conditioned on the transport
binding, nor on the registration fact: on `iosSimulatorArm64`, where the session is a default one and its
identifier is inert, a second instance would still mean two live sessions and two task registries.

The registration fact and the app's admission are what a diagnostic dump reports in place of a resolved tier
(`diagnostic-logging`): together they tell an operator which processes may have written records, and so which
logs to read. Decision record: `changes/both-uploaders-active` (D4).

#### Scenario: The extension is never registrable below iOS 26.1
- **WHEN** every combination of the OS fact, the photo permission and the control channel's switch is
  evaluated
- **THEN** no combination answers true on an OS that lacks the OS-driven mechanism

#### Scenario: A partial grant is never registrable
- **WHEN** photo access is `LIMITED`, on an OS with or without the OS-driven mechanism
- **THEN** the extension is not registrable, because every registration write is refused under that grant

#### Scenario: The transport binding is not an input
- **WHEN** the function's inputs are enumerated
- **THEN** the session kind the app-driven engine transfers over is not among them, and no cell varies by it

#### Scenario: The app engine is one instance for the process
- **WHEN** the registration fact and the app's admission change, and later change back
- **THEN** the same engine is used, its session was never invalidated, and uploads resume without aborting
  the process

### Requirement: Both uploaders may run; an overlap is a duplicate, never a loss

Both uploaders SHALL be active at once wherever the OS allows it: on iOS ≥ 26.1 under a full grant the
extension is registered and the app engine creates, and each process writes ledger records through its own
cycle's `LedgerWriter`. Nothing SHALL hand work from one uploader to the other: no transition cancels,
deregisters or demotes anything to make room for the other uploader, so no transition orphans a `REQUESTED`
row and there is no repair of one — no ritual demote, no stranded rule, no restart signal.

This replaces "exactly one mechanism writes the ledger". That premise was false before it was retired — the
join's load, the leave's clear, the device reset, the app's completion callback and whichever cycle was running
already wrote from both processes — and enforcing it is what made every hand-off between the uploaders cancel or
orphan in-flight work, each orphan needing its own repair. Safety SHALL come from the ledger's write discipline,
not from process exclusivity (`sync-ledger`):

- a cycle selects only `DISCOVERED` rows and records `REQUESTED` only after `createJob` answered created
  (write-after-act, `sync-engine`), so a `REQUESTED` row always had a real job and is never re-picked by either
  process's cycle;
- a completion is recorded through the transport's guarded `markTerminal`, which applies to a `REQUESTED` row
  and to nothing else, so the second completion of a pair is a logged no-op and the row converges;
- there SHALL be no claim step and no owner column.

Two cycles running at the same moment can each create a job for the same `DISCOVERED` key before either records
it. That is a duplicate upload of identical bytes to the same destination object — an idempotent `PUT` — and
SHALL be accepted: a photo is **preferably** uploaded by one uploader, never necessarily. A claim-first design
was rejected because a claim held by a process that then dies is a photo that never uploads — invisible and
unfixable — where the duplicate is harmless. Concurrent cycles are rare by construction: the OS launches the
extension, and the app runs on its own triggers.

Accepted and stated, not built for: a transfer or OS job the platform loses with no completion leaves its row
`REQUESTED` and the photo un-uploaded (never observed; a force-quit was measured to deliver `-999` at the next
launch, which the guarded write maps to `DISCOVERED`); two processes writing the ledger in the same instant
(SQLite's busy timeout; contention unmeasured); and the extension's re-invocation request counting rows the app
requested (the OS throttles re-invocation, and a re-invoked extension with nothing admitted returns quickly).
Decision record: `changes/both-uploaders-active` (D1, D2).

#### Scenario: A second cycle creates nothing for a key already requested
- **WHEN** a cycle in either process has recorded a key `REQUESTED`, and a later cycle in either process runs
- **THEN** the later cycle creates no job for that key

#### Scenario: An overlapping pair converges
- **WHEN** both processes created a job for the same key before either recorded it, and both jobs complete
- **THEN** the first completion settles the row, the second is a logged no-op, and the destination holds one
  object

#### Scenario: Both uploaders run under a full grant
- **WHEN** photo access is `GRANTED` on an OS carrying the OS-driven mechanism, with a joined membership
- **THEN** the extension is registered and the app engine's cycle is admitted, and neither is stood down to
  make room for the other

### Requirement: A presented job whose row is gone is answered and nothing more

A presented job whose key has **no ledger row** SHALL be answered to the platform and SHALL leave no trace,
whether it is a first failure for retry, a terminal outcome, or a retry-spent failure:

- the transport SHALL acknowledge it, as it must every presented job, and SHALL write nothing for it;
- it SHALL NOT be retried, re-created, or reported to the engine. No settle or retry path SHALL record a row
  for a key the ledger does not hold.

A row goes missing by design: an authoritative walk deletes a departed asset's rows whatever their state,
including a `REQUESTED` row whose job is still in flight (capability `sync-ledger`, "Deletion is a presence
diff over an authoritative walk"). The photo was deleted from the library or, under a partial grant,
de-selected. A late outcome for it is expected. Before this rule, a late failure reached the engine,
whose failure record is an upsert guarded only against a *settled* row. That recreated the row as a bare
`DISCOVERED` row (its `assetId` parsed from the key, no capture date). The row was never admitted, so it was
never uploaded or listed. It was never in any walk's window either, so it was never deleted, and it counted
as pending forever. A first-failure retry went further: it re-uploaded the photo the walk had just removed.

The rule SHALL hold on every settle and retry path of the cycle — the narrow settle of a withheld cycle, the
re-creation of retry-spent failures, and the first-failure retry loop — **before** the engine is consulted.
The PhotoKit transport SHALL enforce it on its side as well: it SHALL emit a presented job to the cycle only
when the job's row exists, and SHALL acknowledge a job whose row is gone in place (capability
`ios-photokit-upload`, "Completion and retry adjudication"). So a job the cycle declines is never one left
un-acknowledged (error 50008).

Decision record: `changes/selection-is-the-walk` (D3).

#### Scenario: A late failure for a deleted row records nothing
- **WHEN** a withheld cycle's narrow settle is presented a failure whose key has no row
- **THEN** the job is acknowledged, no row is recorded, and the ledger's pending count is unchanged

#### Scenario: A retry-spent failure for a deleted row is not re-created
- **WHEN** a running cycle is handed a retry-spent failure whose key has no row
- **THEN** no engine event is raised, no job is created, and no row is recorded

#### Scenario: A first failure for a deleted row is not retried
- **WHEN** the platform presents a first failure for retry whose key has no row
- **THEN** the job is acknowledged, not retried, and no `REQUESTED` row is recorded

#### Scenario: A late failure for a present row is still retried
- **WHEN** a presented failure's key has a `REQUESTED` row
- **THEN** it is adjudicated as before: the row returns to `DISCOVERED`, and the retry or re-creation is made

### Requirement: A transition that cannot read the membership defers visibly

An upload transition SHALL NOT act on an unreadable membership, and SHALL log that it deferred.
A membership transition of the upload arm (launch, permission change, override change) that cannot read
the membership SHALL treat it as **unreadable**, never as "not joined" (capability `module-architecture`,
"Reads that can be unknown say so"). It SHALL NOT register, deregister, arm or disarm on the strength of an
unreadable read. It SHALL log that it deferred and why, and the next transition or foreground entry SHALL
reconcile again.

#### Scenario: A launch transition before first unlock

- **WHEN** host assembly runs the launch reconcile while the membership file is unreadable
- **THEN** no registration is written or removed, no engine is armed or disarmed, and the device log
  records the deferral

#### Scenario: A definite absence is still a leave

- **WHEN** the membership file is definitively absent
- **THEN** the transition treats the device as not joined, as before
