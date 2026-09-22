# ios-url-session-upload Specification

## Purpose

The **app-driven uploader**, on every iOS version: the main app process performs uploads itself over a
`URLSession` — a **background** one on every shipped binary —
pumped by `BGProcessingTask`, driving the same shared `UploadCycle` as the OS-driven tier.

It exists because the host app deploys to iOS 18 while the extension target is pinned to 26.1, so without it
a sub-26.1 device could join an event and show status but never contribute a single photo. It is not selected
per OS version any more: from iOS 26.1 it runs **beside** the PhotoKit extension, creating under any usable
grant while the extension creates only under a full one, both writing the one App-Group ledger through guarded
writes (`changes/archive/2026-09-22-both-uploaders-active`).

The pump necessarily reimplements what the OS gives the other tier for free — scheduling, backpressure,
per-slot temp-file staging, a relaunch drain and a heartbeat — which is why its **logic** is
simulator-testable against a fake transport. The **transport** is simulator-testable too, but only over the
simulator target's own binding: a background `URLSession` transfers nothing on that host, so
`iosSimulatorArm64` binds a default session instead and bytes really move (see "The transport binding is
fixed by the compilation target", which also enumerates what such a run does **not** evidence). What stays
device-only is everything that depends on outliving the process — suspension survival, OS relaunch, task
reattachment — plus `BGProcessingTask` timing.

See `ios-photokit-upload` for the OS-driven uploader on iOS ≥26.1.

Decision record: `changes/archive/2026-07-04-add-url-session-upload` (the tier),
`changes/archive/2026-07-12-fix-download-session-lifecycle` (why no lifecycle verb may invalidate the
background session — the `disable` bullet used to instruct exactly that, and the sibling download client
that followed it aborted in production),
`changes/archive/2026-08-25-correct-simulator-background-session-claims` (why a background `URLSession` transfers nothing on
an iOS simulator),
`changes/bind-transport-session-by-target` (why the binding is therefore fixed by the **compilation
target** rather than kept uniform — including the measurement that retired the "it is the only host that
exercises `__NSURLBackgroundSession`" ground, and the daemon's own refusal line, which supersedes the
previous record's note that the cause was inferred rather than stated).

The **App-driven lifecycle** requirement (re-provision, leave) and the tier-force flag were corrected in
`changes/archive/2026-07-12-fix-app-driven-upload-lifecycle`: this spec was originally written against an
*event-scoped* ledger and byte store, a premise already false when it was written — hence its former
"cancel in-flight tasks for the old event" and "leave clears the ledger" bullets. Prefer that record for
those decisions.
## Requirements
### Requirement: Per-slot temp-file staging

The adapter SHALL stage resource bytes to temp files for background upload (a background `URLSession`
uploads from a file, not from in-memory data). It SHALL materialize a resource's temp file **only when
a concurrency slot frees** (per-slot, bounded by the cap) rather than pre-staging the whole library,
so peak temp-file disk is bounded to a handful of resources. Temp files SHALL live in the shared
App-Group container (the same group as the ledger). Each temp file SHALL be deleted on its task's
terminal completion, and a live task's temp file SHALL be deleted when the **leave** cancels that task (see
"App-driven lifecycle"); no other lifecycle verb deletes one. Extraction on a retry MAY re-materialize the file.

A temp file whose transfer vanished with **no** completion ever delivered SHALL be accepted as residue in the
App Group: no cycle enumerates the transport's lost transfers any more, so nothing deletes it. This is bounded
by transfers the OS drops silently, which has never been observed — a force-quit was measured to deliver `-999`
at the next launch, whose completion deletes the file through the ordinary path. Decision record:
`changes/both-uploaders-active`.

#### Scenario: Staging is bounded to open slots
- **WHEN** thousands of resources are pending and the concurrency cap is N
- **THEN** at most ~N resources are materialized to temp files at any time, each deleted on its task's completion

#### Scenario: A leave deletes the staged files of live transfers
- **WHEN** the user leaves the event while upload tasks are in flight
- **THEN** each cancelled task's staged temp file is deleted

#### Scenario: A disarm deletes no temp file
- **WHEN** the app-driven engine is disarmed (photo access revoked) while upload tasks are in flight
- **THEN** no temp file is deleted by the disarm; each is deleted when its task completes

### Requirement: The pump reimplements the OS scheduler

The app-driven tier SHALL provide a `BackgroundUploadPump` (in `:domain` `feature/upload`, platform-free)
that drives `UploadCycle.run()` — the in-app replacement for the OS-owned `process()` scheduler. The
pump SHALL be invoked by six triggers: (a) an arm at a membership transition or launch, (b) app foreground entry, (c) a
`BGProcessingTask` handler, (d) background-`URLSession` completion relaunch
(`handleEventsForBackgroundURLSession`), (e) a per-upload completion delegate callback — which drives a cycle
**only when the app's admission is `Admit`** (see "The delegate records the terminal fact before it
returns") — and (f) a
**silent push for the active event**. The pump SHALL be **single-flight**: at most one
`UploadCycle.run()` executes at a time; concurrent triggers coalesce into a trailing re-run so no two
of this process's cycles write the ledger concurrently (the extension's cycle, a separate process, may overlap
one — see "The app holds a ledger record-writer on every OS version"). On a `PROCESSING` result the pump SHALL re-arm: in the
foreground it SHALL wait for the next completion (which frees a slot) rather than busy-looping the cap;
in a background context it SHALL ensure the next `BGProcessingTask` is scheduled.

On a `SKIPPED` result — the cycle declined because the membership contributes nothing (its selection policy
admits nothing, as for a download-only membership), or because there is no membership at all (capability
`upload-lifecycle`) — the
pump SHALL schedule **nothing**, at every trigger; the transition that makes the engine eligible again arms it.
Every app-side trigger now reaches this engine whatever its state, so an unjoined device's foreground would
otherwise submit a self-re-submitting heartbeat for no event. A non-contributing device
SHALL therefore hold no `BGProcessingTask`, and SHALL stop waking once any outstanding one fires. Re-arming
a device that will never upload would wake it forever to do nothing.

The pump's re-arm decision SHALL be expressed over the `CycleResult` variants exhaustively, so a future
variant cannot silently inherit a re-arm policy nobody chose for it.

#### Scenario: Concurrent triggers do not run cycles in parallel
- **WHEN** a completion callback and a `BGProcessingTask` fire while a cycle is running
- **THEN** they coalesce; exactly one additional cycle runs after the current one finishes

#### Scenario: PROCESSING on a full cap waits for a completion in foreground
- **WHEN** `UploadCycle.run()` returns `PROCESSING` because the cap is full while foregrounded
- **THEN** the pump does not immediately re-run; the next `URLSession` completion re-invokes it

#### Scenario: SKIPPED never re-arms the heartbeat
- **WHEN** `UploadCycle.run()` returns `SKIPPED` at any trigger, including the `BGProcessingTask` handler
  whose re-arm is otherwise unconditional
- **THEN** no `BGProcessingTask` is scheduled, so the device stops waking to upload

#### Scenario: A completion re-pumps only while the app may create
- **WHEN** an upload completion is delivered while the app's admission is not `Admit` (e.g. photo access was
  revoked)
- **THEN** the pump drives no cycle for that completion

### Requirement: A silent push drives an upload scan

A silent push for the device's **active event** SHALL drive an upload cycle on this tier, and SHALL re-arm
the heartbeat. The `BGProcessingTask` heartbeat is scheduled at the OS's discretion and is routinely deferred
well beyond its `earliestBeginDate`; a silent push is the reliable wake, and it arrives precisely when an
event is live, because it is emitted when another member's device drains a cycle that completed an upload
(capability `upload-completion-notify`).

The active-event decision SHALL live in a tested feature, in a receive seam mirroring the download arm's
(`UploadPushReceiver`, `:domain` `feature/upload`; the download arm's is `DownloadPushReceiver`,
`feature/download`), and SHALL NOT be duplicated in the composition root. The cross-arm **fan-out** SHALL
be the `flow/SilentPush` trigger (`:domain` `flow/`, built in `compose/`; it absorbed the former
`FanOutPushReceiver`): one push fans out to each arm's receiver in order (download, then upload on this
tier), isolated so one receiver's failure never robs the other of the scarce wake.

The active-event guard SHALL be **orthogonal** to the direction gate (capability `upload-lifecycle`): the
active-event guard answers "is this push for my current event", the direction gate answers "should this device
ever upload here". A push for an event that is not the active one SHALL drive no cycle — notably a locally-left
event, whose backend membership persists (leave is local-only, capability `leave-event`) and which therefore
keeps pushing this device.

The push handler SHALL release the OS completion handler promptly and SHALL NOT hold it for the cycle: iOS
grants a silent push a short budget, and a library walk can exceed it. The scan is therefore best-effort — a
cycle cut short by suspension leaves only idempotent ledger writes behind, and the next wake's walk is
a full enumeration that simply redoes it.

#### Scenario: A push for the active event drives a cycle and re-arms
- **WHEN** a silent push arrives naming the device's active event on a contributing membership
- **THEN** an upload cycle is driven and the next `BGProcessingTask` is scheduled

#### Scenario: A push for another event drives nothing
- **WHEN** a silent push arrives naming an event that is not the device's active event, including a
  locally-left event the backend still pushes
- **THEN** no upload cycle is driven

#### Scenario: A push to a download-only membership passes the active-event guard and still uploads nothing
- **WHEN** a silent push arrives for the active event on a membership whose direction excludes upload
- **THEN** the receiver drives the pump (the active-event guard passes) and the cycle returns `SKIPPED`, so
  no upload job is created and no heartbeat is scheduled

#### Scenario: The push completion handler is not held for the cycle
- **WHEN** a silent push drives an upload cycle
- **THEN** the OS completion handler is released without waiting for the cycle to drain

### Requirement: Foreground entry re-arms the heartbeat

App foreground entry SHALL re-arm the `BGProcessingTask` heartbeat for a contributing membership, in
addition to driving a cycle.

Without this, the heartbeat's liveness depends entirely on an unbroken chain from a producer start through
each handler's re-submission. A force-quit cancels every pending `BGTaskScheduler` request and the OS does not
relaunch the app until the user opens it — so nothing re-arms until the next producer start (a provision or a
permission grant). Reopening the app, which is exactly when the device is available to be re-armed, did not.

#### Scenario: Foreground recovers a heartbeat lost to a force-quit
- **WHEN** the app is force-quit (cancelling its scheduled `BGProcessingTask`) and the user later reopens it
  on a contributing membership, with no provision or permission transition occurring
- **THEN** a `BGProcessingTask` is scheduled, so the device resumes waking to catch new photos

#### Scenario: Foreground on a non-contributing membership arms nothing
- **WHEN** the app foregrounds on a membership whose direction excludes upload
- **THEN** the cycle returns `SKIPPED` and no `BGProcessingTask` is scheduled

### Requirement: Two background engines — relaunch drain and heartbeat

Background progress SHALL be driven by two mechanisms with distinct roles. (1) The **relaunch
ping-pong** — the background `URLSession` continues transfers after suspension and, on completion while
suspended/terminated, iOS relaunches the app via `handleEventsForBackgroundURLSession`, where the pump
records completions and tops up the queue — SHALL be the primary drain. (2) A **`BGProcessingTask`
heartbeat** SHALL be the cold-start / new-photo kick: while the session is idle (no in-flight task to
trigger a relaunch) but pending or newly-captured work exists, the scheduled task wakes the app to
discover and enqueue, restarting the ping-pong. The `BGProcessingTask` SHALL request
`requiresNetworkConnectivity = true` and `requiresExternalPower = false`. Each handler SHALL re-submit
the next task while an event remains joined (the request is one-shot) and SHALL call
`setTaskCompleted`/handle expiration.

#### Scenario: Completions self-sustain the drain
- **WHEN** background transfers complete while the app is suspended
- **THEN** iOS relaunches the app, the pump records the completions and enqueues more, and this repeats until discovery is exhausted

#### Scenario: Heartbeat catches new photos when the session is idle
- **WHEN** new photos are captured while the app is closed and no upload is in flight
- **THEN** a `BGProcessingTask` (network-gated, power not required) wakes the app to discover and enqueue them, and re-submits the next task

### Requirement: BackgroundScheduler seam

Re-arm scheduling SHALL be expressed as a platform-free seam `BackgroundScheduler`
(`scheduleNext()` / `cancel()`), so the pump's re-arm logic is JVM- and
simulator-testable against a fake. The iOS implementation (`IosBackgroundScheduler`, in
`:adapter:ios:app-only` — the app-only adapter module; before migration step 4,
`:app:ios:url-session-upload`) SHALL back it with `BGTaskScheduler`. The genuinely OS-bound wiring —
`BGTaskScheduler` registration, the `URLSession` delegate, and `handleEventsForBackgroundURLSession`
forwarding — SHALL live in the thin, untested Swift shell and forward into the Kotlin pump.

#### Scenario: Re-arm logic is testable without a device
- **WHEN** the pump's re-arm behavior is tested
- **THEN** it runs on JVM and `iosSimulatorArm64` against a fake `BackgroundScheduler` and a fake `UploadCycle`, with no `BGTaskScheduler` dependency

### Requirement: App-driven lifecycle
On every iOS version the membership lifecycle of the app-driven engine SHALL be performed by the app
in-process and ordered. The **decision** of
which verb fires on which transition belongs to `upload-lifecycle` ("Membership transitions reconcile the upload
mechanisms in one tested place"); this requirement binds the app-driven engine's three verbs. They are
independent of the PhotoKit extension's registration, which on iOS ≥26.1 spans the membership from join to
leave wherever the OS allows it (capability `ios-photokit-upload`); the app engine is armed beside it.

- **arm** (a join, any reconfigure, a permission change, or a launch, whenever photo access is usable —
  `GRANTED` or `LIMITED`): run a cycle immediately, and **schedule the first
  `BGProcessingTask`** (the heartbeat is one-shot, so nothing else would arm it after a force-quit until the next
  foreground). Arming repairs no ledger row. It does not read the membership's direction: on a membership that
  contributes nothing the cycle declines on the selection policy and returns `SKIPPED`, so the pump schedules
  nothing. A re-provision of the already-joined event (`SwitchDecision.Stay`) arms nothing.
- **disarm** (a revocation of usable access, and a leave): cancel the scheduled `BGProcessingTask` — and
  **nothing else**. It SHALL NOT cancel an in-flight transfer, delete a staged temp file, clear the ledger, or
  repair a row. A revocation therefore stops **new** creation (the app's admission withholds) and new wakes (the
  heartbeat); transfers already in flight finish, and their completions are recorded by the delegate's guarded
  write (see "The delegate records the terminal fact before it returns") and drive no cycle while the admission
  withholds.
- **cancelTransfers** (the **leave only**, including the leave a switch performs): cancel the in-flight upload
  **tasks** and delete their staged temp files. The background `URLSession` itself SHALL be left intact — see
  "Cancellation never invalidates the background session" below. No other transition SHALL cancel a transfer.
- **switch** (a valid event link for a **different** event; re-confirming the already-joined event is not a
  switch, and neither disarms, cancels, nor resets the ledger): a leave followed by a join (capabilities
  `upload-lifecycle`, `join-event`). The **leave runs first** — deregistering the extension where it is
  registered, disarming this engine and cancelling its transfers, deleting their staged temp files, with the
  session left intact — then the provision's **join-time load** clears the ledger and re-seeds it from the
  per-device listing (`resetTo` on a
  successful fetch, `clear()` on a failed one — capability `join-event`), then the new `eventId` is persisted,
  and only then does the join transition arm the engine (and, where registrable, register the extension). The
  first cycle the arm runs therefore already sees the
  seeded rows, so already-stored resources are `COMPLETED` before any upload job is created; the cycle itself
  seeds nothing and consults no join marker. Cancelling costs at most a re-upload of what was in flight — to the
  same device-partitioned, event-independent destination (`/files/devices/<deviceId>/<filename>`), so it is an
  idempotent overwrite — and whatever landed before the cancel is in the listing the load reads. A completion
  delivered after the load finds no row (or a seeded one) and changes nothing the load did not already account
  for. There SHALL be no cross-process race: the join's registration toggle meets no live job of the new
  membership, because the leave already deregistered the old one.
- **leave**: disarm and cancel the transfers, then **clear the upload ledger**, then clear the stored `eventId`
  (the order is
  `LeaveEvent`'s — capability `leave-event`). The cancel and the clear are the leave's, not the disarm's. The ledger is the
  current membership's share set, so a device that has left holds none; the next join re-seeds it from the
  per-device listing, so nothing already stored re-uploads unless that fetch fails. A completion delivered after
  the clear finds no row, and its outcome is acknowledged and discarded — its bytes are on the backend, where the
  next join's listing finds them. The cycle holds no leave-side action: after the leave it reads the membership
  as absent and uploads nothing.

The four app-side triggers (foreground, silent push, heartbeat, selection change) reach this engine whether or
not it is armed; its cycle's entry gate decides (`upload-lifecycle`, "Triggers are delivered to the mechanism
and declined explicitly").

Decision record: `changes/both-uploaders-active` (D5, D6).

#### Scenario: Re-provision is an in-process ordered sequence

- **WHEN** a new valid event link for a different event is scanned
- **THEN** the app runs the leave (disarming the engine and cancelling its transfers), clears the ledger and re-seeds it from the per-device listing, persists the new event, and only then arms the engine — so the first cycle finds already-stored resources `COMPLETED` before any upload job is created, with no cross-process timing hazard

#### Scenario: A switch cancels in-flight transfers before the ledger is reset

- **WHEN** an event switch occurs while uploads are in flight
- **THEN** those transfers are cancelled and their staged temp files deleted before the ledger is cleared and re-seeded; what landed before the cancel is seeded `COMPLETED` from the listing, and the rest re-uploads to the same device-partitioned destination

#### Scenario: Re-confirming the joined event does nothing to the engine

- **WHEN** a valid event link for the already-joined event is scanned while uploads are in flight
- **THEN** the engine is neither armed, disarmed, nor cancelled, and the in-flight transfers continue

#### Scenario: Arming arms the heartbeat

- **WHEN** the app-driven engine is armed on a contributing membership
- **THEN** a cycle runs, and the first `BGProcessingTask` is submitted

#### Scenario: Arming a download-only membership schedules nothing

- **WHEN** the app-driven engine is armed on a membership whose selection policy admits nothing
- **THEN** the cycle returns `SKIPPED`, no upload job is created, and no `BGProcessingTask` is submitted

#### Scenario: Disarming preserves the ledger and the transfers

- **WHEN** the app-driven engine is disarmed because photo access is revoked while uploads are in flight
- **THEN** only the scheduled `BGProcessingTask` is cancelled; the in-flight tasks continue, each completion is
  recorded into the ledger, and every other ledger row is left intact

#### Scenario: Leave cancels transfers and clears the ledger

- **WHEN** the user leaves the event
- **THEN** in-flight tasks and the scheduled `BGProcessingTask` are cancelled, then the upload ledger is cleared, then the stored `eventId` is cleared — and joining any event afterwards re-seeds the ledger from the per-device listing, so nothing already in the device's byte partition re-uploads unless that fetch fails

#### Scenario: Only the leave cancels transfers

- **WHEN** a reconfigure, a permission change, a launch, or a re-provision of the joined event occurs while
  uploads are in flight
- **THEN** no upload task is cancelled

### Requirement: Cancellation never invalidates the background session

A lifecycle verb that stops transfers SHALL cancel the individual `URLSession` **tasks** — only the leave's
`cancelTransfers` does, which a switch runs through its leave — and no lifecycle verb — **disarm**,
**re-provision**, **leave**, or **switch** — SHALL invalidate the background
`URLSession`.

A background `URLSession` is a process-lifetime singleton. Invalidation is **terminal**: creating a task on
an invalidated session throws an Objective-C `NSException`, which Kotlin/Native cannot catch and which
aborts the process. Because every one of these verbs is followed by a later upload — a re-grant after a
revoke's disarm, a fresh cycle after a re-provision, a new event after a switch, a re-join after a leave — a
session destroyed as a means of
cancelling is a crash awaiting the next cycle. Invalidation is reserved for process teardown or for
deliberately discarding a session to rotate its identifier, and is used for neither here. The session
identifier SHALL remain stable so `handleEventsForBackgroundURLSession` can re-adopt it across launches.

This requirement records the rule the tier already implements, and removes the previous instruction to
"invalidate/cancel the background `URLSession`" on disable — which, if implemented literally, would abort
the app on the next upload after a revoke→re-grant. The same rule governs the download client
(`photo-download`), where following that instruction did abort the app in production.

#### Scenario: Re-join after a leave uploads without a crash

- **WHEN** the user leaves the event (cancelling transfers) and later joins again, and a cycle runs
- **THEN** upload tasks are created on the still-valid background session and the app does not abort

#### Scenario: Re-grant after a revoke uploads without a crash

- **WHEN** photo access is revoked (disarming the engine) and later granted again, and a cycle runs
- **THEN** upload tasks are created on the still-valid background session and the app does not abort

#### Scenario: No lifecycle verb invalidates the session

- **WHEN** a leave, or a switch through its leave, stops in-flight transfers, or the engine is disarmed
- **THEN** only individual upload tasks are cancelled (by the leave) and the background `URLSession` remains
  valid and reusable

### Requirement: Module placement and testing split

The app-driven adapters (`IosUrlSessionUploadPlatform`, `IosBackgroundScheduler`) SHALL live in the
app-only adapter module `:adapter:ios:app-only` — linked only by the main app process, never the
extension (before migration step 4 they lived in `:app:ios:url-session-upload`, deleted by that
step) — depending on the extension-safe adapter module `:adapter:ios:ext-safe` for the shared
upload-request builder. The shared `IosDiscovery` walk is bound by the app's composition root as the
`UploadDiscovery` port, not held by the adapter. The
`BackgroundUploadPump` and `BackgroundScheduler` pump logic SHALL live in `:domain` — the pump in
`feature/upload`, the scheduler seam in `ports/` (seated by migration step 5; formerly
`:capability:upload`) — `jvm()`-enabled and harness-covered. The pump and scheduler logic SHALL be
tested on JVM and
`iosSimulatorArm64`; the `URLSession` adapter SHALL be faked in the harness (like the PhotoKit
adapter). The transport MAY be exercised end-to-end on a simulator, over that target's **default**
session binding (see "The transport binding is fixed by the compilation target") — which evidences the
request, the delegate, the staging move and the outcome path, and evidences **none** of the
background-session properties that requirement enumerates. `BGProcessingTask` **timing** and true-suspend
behaviour remain device-only.

#### Scenario: Pump lives in the platform-free core
- **WHEN** the modules are assembled
- **THEN** `BackgroundUploadPump` is in `:domain` `feature/upload`, and the iOS adapters are in `:adapter:ios:app-only`, which composes `:adapter:ios:ext-safe`; the pump and the `uploadCore`-assembled cycle are composed with the adapters in the app's composition root, not by the adapter module

#### Scenario: A simulator end-to-end run is scoped to what it shows
- **WHEN** the transport is exercised end-to-end on a simulator
- **THEN** the bytes move and the outcome path is exercised, and the run is recorded as evidencing neither
  suspension survival nor OS relaunch

### Requirement: Pump triggers an in-process status refresh after each cycle

On the app-driven tier the pump SHALL, after **each** `UploadCycle.run()` (it runs in the main
app process), trigger an in-process status refresh — a re-read of the ledger
counts (`LedgerCountsSource.refresh()`, per `sync-status`) — so foreground upload status moves at
cycle granularity, not only at the foreground-gated poll's cadence. (The cross-process Darwin
liveness notification this requirement used to contrast against is deleted on every tier —
migration step 12; the poll in `sync-status` is the cross-process mechanism's replacement, and
this pump-side refresh stands beside it unchanged.) The refresh SHALL be a fire-and-forget side
effect that does not alter the pump's single-flight cycle behavior or its `PROCESSING` re-arm.

#### Scenario: A completed pump cycle refreshes status in-process
- **WHEN** an app-driven `UploadCycle.run()` completes (any result)
- **THEN** the pump triggers the in-process ledger-counts refresh, and posts no cross-process
  notification

#### Scenario: The refresh does not disturb the cycle scheduler
- **WHEN** the in-process refresh runs after a cycle
- **THEN** the pump's single-flight behavior and `PROCESSING` re-arm are unaffected

### Requirement: The app-driven cycle skips on an unreadable membership

The app-driven tier SHALL reach its cycle-entry decision through the three-state membership read
(capability `event-link`) and the shared decision function (capability `upload-lifecycle`). It SHALL NOT
reach it through the two-state config state flow, which cannot express "unreadable" and reports it as
`null` — indistinguishable from a leave.

The tier SHALL NOT carry a cycle-entry translation of its own: its cycle is assembled by the shared
composition `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition"),
whose entry gate is port-pure — one fresh `ConfigReader.read()` per cycle, the identity probe, and the
host — per `upload-lifecycle` "The upload cycle owns its entry decision". The tier's former
controller-local gate additionally refreshed the UI-facing config `StateFlow` each cycle; that side
effect is not part of the entry gate and is owned by the app shell's protected-data unlock hook
(decision record: `changes/archive/establish-shared-composition` D1).

This tier invokes its own cycles from the app process, from four triggers (start, foreground, background
task, session events) plus silent push. Each SHALL produce **Skip** on an unreadable membership: no
ledger write, clear or reset, and no upload job. The exposure is
narrow — the membership item is stored `AfterFirstUnlock`, so an unreadable read needs a boot with no
unlock — and the requirement stands regardless: the accessibility attribute makes a false leave
improbable, the three-state read makes it impossible.

The tier SHALL probe the device identity per cycle rather than resolving it once into a held value. A held
identity cannot express "unreadable this cycle": an unresolvable identity throws out of whatever first
touches it instead of skipping cleanly. The probe is per-process in effect on both tiers already — the
identity caches for the process lifetime, and the OS-invoked tier's per-cycle probe is per-process because
its process dies each cycle.

#### Scenario: A background task on an unreadable membership does not leave the event
- **WHEN** the app-driven tier runs a cycle from its background task and the membership read fails because
  protected data is unavailable
- **THEN** the cycle skips, the ledger is untouched, and the device is still joined on the next readable
  cycle

#### Scenario: An unresolvable device identity skips rather than throwing
- **WHEN** the app-driven tier runs a cycle and the device identity cannot be resolved
- **THEN** the cycle skips cleanly and no error escapes the cycle

#### Scenario: A definitely-absent membership uploads nothing on this tier
- **WHEN** the app-driven tier runs a cycle after a leave, and the membership read reports no item
- **THEN** the cycle takes the not-joined path: it uploads nothing and writes nothing to the ledger —
  the leave itself already cleared it

#### Scenario: The tier's cycle is the shared composition
- **WHEN** `UrlSessionUploadController` assembles its upload cycle
- **THEN** it calls `uploadCore` over its ports — it constructs no gate, cycle, join marker, or
  device-manifest producer of its own, and its device-manifest uploader is `:adapter:generic:app`'s
  `HttpEnrollment`

### Requirement: The app-driven root states its selection policy explicitly

The app-driven tier's composition SHALL supply every selection and side-effect port explicitly (capability
`upload-lifecycle`). No port on this tier's controller SHALL carry a permissive default — in particular the
denylisted-album source, whose omission would let this tier upload the albums the OS-invoked tier refuses.

#### Scenario: The tier cannot be composed without its album policy
- **WHEN** the app-driven controller is constructed without a denylisted-album source
- **THEN** it does not compile

### Requirement: Background-URLSession BackgroundTransfer implementation

The app-driven tier SHALL implement the existing `BackgroundTransfer` port (`:domain` `ports/`) with a
background-`URLSession`-backed adapter (`IosUrlSessionUploadPlatform`) — **not** a new seam — so
`UploadCycle` runs unchanged. The adapter SHALL map the seam verbs to `URLSession` semantics:

- `createJob(request, resource)` SHALL start a background `uploadTask(fromFile:)` for the staged
  resource, tag the task with the ledger key via `taskDescription`, and return `CREATED`; when the
  concurrency cap is already reached — measured against the session's live task set — it SHALL return
  `LIMIT_EXCEEDED` (the adapter's backpressure, and the only bound the cycle's top-up obeys), and on a failure
  to start (e.g. unusable staged file) it
  SHALL return `FAILED`.
- `fetchRetryJobs()` SHALL return an **empty** list — this platform grants no OS-sponsored single
  retry; a terminal failure is recorded `DISCOVERED` by the delegate and re-uploaded from a later enumeration.
- `drainTerminals()` SHALL return an **empty** list on this tier and SHALL perform no reconciliation of its
  own. Terminal outcomes are recorded into the ledger by the delegate as they are delivered (see "The delegate
  records the terminal fact before it returns"), so no terminal fact crosses the port, and this tier has
  nothing for the cycle to re-create in-cycle. It SHALL delete the resource's staged temp file when the
  transfer terminates — the file is unusable from that moment; what a transfer that vanished with no
  completion leaves behind is accepted residue (see "Per-slot temp-file staging").
- `retryJob(job, request)` SHALL be implemented as cancel-and-recreate.

The seam SHALL carry no capacity read and no live-set, lost-set, or discard member: the cycle creates until
`createJob` refuses, and no cycle reconciles stranded rows (decision record: `changes/both-uploaders-active`).

The adapter SHALL NOT serve the cycle's library reads: discovery and key resolution are the shared
`UploadDiscovery` port the composition root binds (see "Ledger keys resolve to uploadable resources"). It
SHALL receive the ledger only as a `TransferRecord` (`sync-ledger`, "Reader and writer capability split"),
never as a `LedgerStore`.

Correctness SHALL rely on **at-least-once** delivery: keys are deterministic and the edge PUT is
idempotent (a re-PUT overwrites the same object), so a duplicate send is harmless. At-least-once bounds what
a **duplicate** costs; it SHALL NOT be read as licence to lose a delivered outcome. A terminal fact the
platform delivers once is recorded once, durably, and losing it costs a re-upload plus a status that trails
reality until the re-upload completes — which is a defect, not an accepted consequence of at-least-once.

#### Scenario: fetchRetryJobs is empty on this platform
- **WHEN** `UploadCycle` calls `fetchRetryJobs()` on the `URLSession` adapter
- **THEN** it returns an empty list, and a failed upload is instead recorded `DISCOVERED` by the delegate and re-uploaded from a later enumeration

#### Scenario: drainTerminals is empty on this platform
- **WHEN** `UploadCycle` calls `drainTerminals()` on the `URLSession` adapter
- **THEN** it returns an empty list, because every terminal outcome has already been recorded into the ledger

#### Scenario: A recreated upload keeps its original content type
- **WHEN** a key whose upload failed, returning its row to `DISCOVERED`, is re-uploaded from a later enumeration
- **THEN** the request carries the content type recorded on its ledger row, so the stored object
  is typed identically on this tier and on the PhotoKit tier

#### Scenario: Own cap surfaces as LIMIT_EXCEEDED
- **WHEN** `createJob` is called while the session already holds the cap of live tasks
- **THEN** it returns `LIMIT_EXCEEDED`, so `UploadCycle` stops creating for that pass, reports it truncated,
  returns `PROCESSING`, and the pump re-arms

#### Scenario: A terminated transfer's staged file is deleted
- **WHEN** a task reaches a terminal outcome
- **THEN** the resource's staged temp file is deleted, making no OS call

#### Scenario: The adapter holds no ledger store and reads no library
- **WHEN** the app-driven adapter is constructed
- **THEN** it is given a `TransferRecord` and no `LedgerStore` or discovery, and the cycle's walk and key
  resolution reach the library only through the root-bound `UploadDiscovery`

### Requirement: The app-driven tier serves limited memberships with selection-driven triggers

The app-driven mechanism SHALL serve `LIMITED` memberships unchanged in its transport, staging,
ledger-writer, and cycle semantics — the measured fact grounding this tier's limited role is that it
uploads under `.limited` on the first attempt with the full cycle (bytes, manifest, notify). What
differs under `LIMITED` is **when the cycle reads the library**: the pump's autonomous triggers
(foreground entry, silent push) SHALL NOT initiate a library read while permission is `LIMITED`
(capability `limited-photo-access`, "No autonomous library reads"); cycles that read run from the
cold-launch baseline and from selection-change consumption, and continuation triggers
(`onUploadCompleted`, session events, the heartbeat) SHALL drain already-enqueued work without a fresh
library read.

#### Scenario: A selected photo uploads under limited via the ordinary cycle
- **WHEN** a `LIMITED` member with upload-inclusive direction selects an in-scope photo and the
  selection-change consumption enqueues it
- **THEN** the app-driven mechanism uploads it exactly as it would any enqueued work — background
  session, staging, ledger `COMPLETED`, manifest, notify

#### Scenario: Continuation drains without re-reading the library
- **WHEN** several enqueued uploads complete one after another under `LIMITED`
- **THEN** the continuation cycles upload the remaining queue without initiating a new library read

### Requirement: A coalesced pump trigger keeps its obligations

A coalesced pump trigger SHALL NOT be discarded. The pump admits one drain at a time and coalesces
concurrent triggers into a trailing re-run; the coalescing caller SHALL await the in-flight drain and,
when it ends, SHALL apply **its own** trigger's re-arm policy against that drain's final `CycleResult`.

Returning immediately drops two obligations at once, and both cost the app future background wakes. A
caller that awaited nothing cannot be the work an OS completion handler is held for — the handler is
released against a cycle still running elsewhere. And a caller that skipped its re-arm leaves the
`BGProcessingTask` chain unarmed, which is fatal because the request is one-shot: the heartbeat then
resumes only when the user next foregrounds the app.

The re-arm SHALL be evaluated against the drain's result rather than assumed, because the coalesced caller
ran no cycle of its own and only that result answers whether work remains. A `SKIPPED` drain SHALL
therefore still arm nothing, from any trigger, exactly as an uncoalesced one does.

The awaited span is the in-flight drain **including** the re-run the coalescing caller requested. The pump
SHALL NOT bound that wait, and nothing else bounds it either: an OS receipt bounds when the *handler* is
released (capability `ios-app-shell`), not how long the awaiting call takes. So a drain that never ends
holds its coalesced callers indefinitely; what is guaranteed is only that the OS is answered on time
regardless, and that the drain is never cancelled to achieve it.

#### Scenario: A coalesced background-session trigger awaits the drain

- **WHEN** background-session events are delivered while a drain started by a completion is already in
  flight
- **THEN** the trigger coalesces, awaits that drain, and returns only after it has ended — so the OS
  handler held for it is not released against a running cycle

#### Scenario: A coalesced heartbeat still re-submits

- **WHEN** a `BGProcessingTask` handler fires while a drain is already in flight
- **THEN** it coalesces, awaits the drain, and re-submits the next task, because its trigger's re-arm is
  unconditional

#### Scenario: A coalesced relaunch trigger re-arms only on remaining work

- **WHEN** a background-session trigger coalesces and the drain it awaited ends `COMPLETED`
- **THEN** no next task is scheduled; had the drain ended `PROCESSING`, one would be

#### Scenario: A coalesced trigger against a declining membership arms nothing

- **WHEN** a trigger coalesces into a drain that ends `SKIPPED`
- **THEN** nothing is scheduled, whatever the coalescing trigger's own policy would otherwise be

### Requirement: The transport binding is fixed by the compilation target

The app-driven tier's transport SHALL be bound per **compilation target**, never chosen at runtime.
`iosArm64` — every shipped binary — SHALL use a **background** `URLSession`
(`backgroundSessionConfigurationWithIdentifier`), unchanged in every respect including its session
identifier and its `discretionary` / `sessionSendsLaunchEvents` / `allowsCellularAccess` values.
`iosSimulatorArm64`, whose output only ever runs on a simulator, SHALL use a **default** session
configuration.

A device binary SHALL contain **no route** to the default binding, so there is no runtime discriminator
that could be taken wrongly on a device. There SHALL be **no host determination anywhere** in the
composition or the adapters: the process SHALL NOT read `SIMULATOR_DEVICE_NAME` (or any equivalent),
SHALL NOT inspect the running host, and SHALL NOT branch on it in any composition root — `:app:ios` is
wiring-only and decides nothing here. Both app-process transports (`IosDownloadTransport` and
`IosUrlSessionUploadPlatform`, the latter also serving `photo-download`) SHALL obtain their configuration
from **one** seam, so the two cannot diverge.

**Why the simulator cannot use the shipped binding.** A background `URLSession` does not transfer on an
iOS simulator for any third-party process. `nsurlsessiond` resolves each client's **bundle identifier** as
it evaluates the incoming XPC connection, and rejects a client that has none — which is every process an
app author can build there, **including a real installed app declaring a valid `CFBundleIdentifier`**. The
daemon states this as its reason, at error severity:

```
Evaluating new XPC connection … from pid <n> … with client bundle identifier (null)
Process with pid <n> does not have a bundle ID, rejecting connection
… invalidated … xpc_connection_cancel()
```

The client observes `NSCocoaErrorDomain` **4097** (`NSXPCConnectionInterrupted` — accepted, then torn
down; NOT `4099` `Invalid`, and NOT `4102` `CodeSigningRequirementFailure`), then *"failed to create a
background NSURLSessionDownloadTask, as remote session is unavailable"*, and every transfer ends
`NSURLErrorDomain / -1`. Apple's own simulator processes resolve to real bundle identifiers and their
background sessions work.

Measured 2026-08-25 on macOS 26.5.2 / Xcode 26.6, iOS 26.2 and 26.5, with a **foreground control
succeeding against the same URL in the same process**; three client shapes (a bare Kotlin/Native test
binary, an installed signed app, an installed unsigned app) all failed identically, and six candidate fixes
were tested — ad-hoc signature, an Apple Development identity, no signature at all,
`application-identifier`/`team-identifier`/`get-task-allow`, a second runtime, and any entitlement — none
of which works. ⏰ Re-measure at the next iOS major. Decision records:
`changes/archive/2026-08-25-correct-simulator-background-session-claims` (the refusal and its mechanism),
`changes/bind-transport-session-by-target` (this binding, and the quoted refusal line — which supersedes
that record's statement that the daemon "does not state that as its reason").

**What the simulator binding does NOT provide, and SHALL NOT be claimed to.** A default session runs
in-process and dies with it. It SHALL NOT be treated as evidence of any of:

- transfers continuing across app suspension or termination;
- the OS relaunching a terminated app to deliver `handleEventsForBackgroundURLSession` — device-only by
  vendor guidance (Quinn, *Testing Background Session Code*: "Test on a real device, not in Simulator";
  r. 16532261), and independently unmeasurable there since no transfer can outlive the process;
- reattachment to a prior process's tasks — `getAllTasks` can never find one;
- the behaviour of `__NSURLBackgroundSession`, including the invalidation defect
  (`changes/archive/2026-07-12-fix-download-session-lifecycle` D5). Measured 2026-08-25: after the daemon
  rejects and cancels the connection, the client session does **not** call `didBecomeInvalidWithError`
  (observed for ~10 s after the transfer settled, n=1), so that host never reaches the invalidation path
  the defect lives on.

Because a default session never sends `URLSessionDidFinishEventsForBackgroundURLSession`, a
`handleEventsForBackgroundURLSession` wake on that target holds its receipt to the deadline and expires
(`ios-app-shell`; `architecture-guards`, "OS completion handlers are held in one type"). That expiry SHALL
be **predicted rather than diagnosed**: the process SHALL state its binding and this consequence when the
session is constructed, so the expiry line is not read as a fault. Nothing SHALL synthesise the drain — a
transport that reported events drained without the OS having delivered any would make a simulator run
indistinguishable from a device one, which is exactly the false confidence
`fix-download-session-lifecycle` D5 refused.

The binding SHALL be **reportable**: the process SHALL expose which binding it holds, so a caller reads it
rather than inferring it from a log line or a stall.

This supersedes `changes/archive/2026-08-09-delete-simulator-session-downgrade` **D1** and
`changes/archive/2026-08-25-correct-simulator-background-session-claims` **D1**; neither archive is edited.
The first's ground — "with no behavioural difference between hosts there is no axis" — rested on a probe
that aimed at a closed port and read `NSURLErrorUnknown` as a connection refusal. The second declined this
binding on the ground that it "removes the only host that exercises `__NSURLBackgroundSession`", and left
the door open in terms ("If it is ever wanted, it is a separate change with its own proposal"); the
measurement above retires that ground, because the simulator never reaches that class's invalidation path.
What is superseded in both is the ground, never the refusal of a **runtime** host determination, which
this requirement restates unchanged.

#### Scenario: A shipped binary contains no route to the default binding

- **WHEN** the `iosArm64` binary is built
- **THEN** it contains only the background configuration, no runtime host check exists anywhere in the
  composition or the adapters, and no code path in it can yield a default session configuration

#### Scenario: The device binding is unchanged by the seam

- **WHEN** the app-driven tier runs on a physical device, before and after this seam is introduced
- **THEN** it creates a background `URLSession` with the same identifier and the same `discretionary`,
  `sessionSendsLaunchEvents` and `allowsCellularAccess` values in both cases

#### Scenario: Both transports share one binding

- **WHEN** the download transport and the app-driven upload platform each construct their session
- **THEN** both obtain the configuration from the same seam, so no build can hold a background binding for
  one and a default binding for the other

#### Scenario: A simulator transfers bytes, over a session that survives nothing

- **WHEN** the app-driven tier starts an upload, or the download transport starts a transfer, on an iOS
  simulator against a reachable server
- **THEN** the transfer completes over a default session, and the run is not treated as evidence of
  suspension survival, OS relaunch, task reattachment, or `__NSURLBackgroundSession` behaviour

#### Scenario: A background-events wake on a simulator expires, and says so in advance

- **WHEN** a `handleEventsForBackgroundURLSession` wake is driven on a simulator
- **THEN** the receipt is held to its deadline and expires because the session never reports its events
  drained, the process has already stated that this binding cannot report them, and no drain is synthesised

#### Scenario: The binding is readable, not inferred

- **WHEN** a caller asks a running process which transport binding it holds
- **THEN** it is answered directly, without reading a log line or waiting for a transfer to stall

### Requirement: The delegate records the terminal fact before it returns

The `URLSession` task-completion delegate SHALL record the terminal outcome into the ledger —
`COMPLETED` on success, `DISCOVERED` otherwise (the platform's failed outcome returns the row to the ledger's
work read) — through the guarded, non-suspending `markTerminal` of the
`TransferRecord` it is given (`sync-ledger`), **synchronously, before the callback returns**. It SHALL NOT
hold the outcome in process memory for a later cycle to collect. Success is recorded as the settled state:
nothing a completion used to trigger is still owed, so no later cycle reads or re-settles the row.

The forcing fact: iOS delivers a background-`URLSession` completion **once**.
`URLSessionTask.State.completed` is documented as *"the task has completed (without being canceled), and the
task's delegate receives no further callbacks"*, and `handleEventsForBackgroundURLSession` delivers the
events *"waiting to be processed"* — a queue of undelivered events, drained once. No API returns a
completion already delivered, and re-adopting the session by identifier re-delivers only what is still
pending. A fact held in memory across process death is therefore unrecoverable, and the row stays
`REQUESTED` with no live task — which nothing ever returns to the work read, since no cycle reconciles
stranded rows (decision record: `changes/both-uploaders-active`), so that photo would never upload.
**Expiry trigger:** the next iOS major, or a device log showing a delivered completion for a task created in
an earlier process.

Synchrony is required, not incidental: after the callback returns the app's continued runtime is not
guaranteed, so work merely scheduled at that point races the system's willingness to keep running the
process. A write whose guard applies to no row SHALL be logged and SHALL NOT be silent.

A completion SHALL **always** be recorded, whatever the app's admission — a transfer that outlived a
revocation finishes and records. After recording, the completion SHALL drive a cycle **only when the app's
admission is `Admit`** right now. That decision SHALL live in the tested pump (`BackgroundUploadPump`'s
completion trigger takes the current admission), never in the shell, which forwards every completion. A late
completion delivered after a revocation therefore records its row and drives nothing — the 2026-09-16 field
observation was late `-999`s after a hand-off each driving an app cycle. Decision record:
`changes/both-uploaders-active` (D7).

#### Scenario: A completion survives process death

- **WHEN** an upload completes, its delegate callback returns, and the process is killed before any cycle
  runs
- **THEN** the next process reads the row as `COMPLETED`, counts its photo completed, and creates no upload
  job for that key

#### Scenario: The recorded state distinguishes success from failure

- **WHEN** a task completes with a transport error or a non-2xx status
- **THEN** the row is recorded `DISCOVERED`, not `COMPLETED`

#### Scenario: A guarded write that applies to nothing is reported

- **WHEN** the delegate records a terminal outcome for a key whose row is not `REQUESTED`
- **THEN** nothing is written and the outcome is logged, so the un-applied write is visible in a device log

#### Scenario: A late completion after a revoke records and drives nothing

- **WHEN** a transfer in flight when photo access was revoked completes afterwards
- **THEN** its row is recorded through the guarded write, and no cycle is driven by that completion

#### Scenario: A completion while the app may create re-pumps

- **WHEN** a transfer completes while the app's admission is `Admit`
- **THEN** its row is recorded and the pump drives a cycle, which tops up from the ledger

### Requirement: The adapter holds no in-process task registry

The app-driven adapter SHALL hold **no** process-lifetime record of in-flight uploads. Every fact it needs
SHALL be derived: the staged file's path from the ledger key, the request's content type from the ledger
row, the live tasks from the session's own `getAllTasks`, and a resource from the row's `assetId`.

The adapter's concurrency cap SHALL therefore be measured against the **session's live task set**, not
against an in-process count. An in-process count is empty after a relaunch while the OS still holds live
tasks, so the cap does not bind across process death and a relaunch can run more concurrent transfers than
the cap allows.

#### Scenario: The cap binds across a relaunch

- **WHEN** the app relaunches while the OS still holds its cap of live upload tasks
- **THEN** `createJob` reports the cap as reached and starts no further transfer

#### Scenario: A staged file is found without a remembered path

- **WHEN** a staged file must be located for a key in a process that did not create it
- **THEN** its path is derived from the key alone

### Requirement: The producer tops up from the ledger, not from the walk's output

On this tier the upload cycle SHALL enqueue work from the ledger's rows that need a job (capability
`sync-ledger`), resolving each row's resource on demand. It SHALL NOT enqueue from the discovery walk's
return value: the walk's job is to **record** what it found, and creating jobs from what it happens to
be holding is what made the cycle unable to resume work it had already seen.

A cycle SHALL still walk the library, because that is the only way to learn what the library holds.
Every walk is a full enumeration (capability `ios-photokit-upload`, "In-extension discovery by full
enumeration"; this tier binds the same `IosDiscovery`), including the walk of a cycle a completion
triggered. What bounds its cost is that it reads resources only for the assets the ledger does not fully
know (capability `sync-ledger`, "A walk re-reads only the assets the ledger does not fully know"), so a
completion-triggered cycle over a fully-recorded library pays the fetch and the per-asset facts, and no
resource read.

This is what makes the tier's concurrency cap a throughput bound rather than an architectural one.
Before it, the only source of work was the walk's return value, so freeing one slot cost a full library
enumeration to refill it: measured on device (build 0.3(605), iPhone11,2 / iOS 18.7.9), 6.1–7.2 seconds
of PhotoKit XPC over 224 candidates to enqueue two to four resources, repeated 26 times in two hours
without ever draining. (That per-walk figure is situational, not intrinsic: the same operation
measured 145 ms for 1084 candidates on an idle iPhone12,8 / iOS 26.6. What the requirement rests on
is the **repetition**, not the cost of any one walk.) With every walk a full enumeration, the fetch is
repeated per cycle again; what is no longer repeated is the resource read of every admitted asset, and
no refill depends on the walk at all.

What is CREATED SHALL be bounded only by **the platform's own refusal**, never by a guess at its capacity.
The cycle SHALL walk the admitted rows **one at a time**: resolve the row through `UploadDiscovery`, create
its job, and stop the **whole pass** at the first `LIMIT_EXCEEDED`, before resolving the next row. There
SHALL be no capacity read, no fixed batch and no resolve chunk. Both transports refuse honestly: this tier's
`createJob` counts the session's live tasks, so its cap binds across a relaunch, and PhotoKit refuses at its
own job limit.

Resolving a row costs a synchronous platform round-trip that nothing can interrupt, measured at **~4.5 ms per
request plus ~3.45 ms per photo** (rig probe, SE2 / iOS 26.6, 2026-09-22; 100 distinct images per run, two
rounds plus a warm repeat, no cache effect). For 100 photos that is 0.80–0.94 s one at a time, against
0.46 s in fours and 0.37 s in sixteens. The difference is accepted for simplicity. It applies only under a
full grant: under a partial grant keys resolve from the selection snapshot already in hand, with no platform
call (see "Ledger keys resolve to uploadable resources"). The earlier figure of "11 ms for one key"
understated the per-photo cost this call carries.

Decision records: `changes/both-uploaders-active` (D9), and `changes/selection-is-the-walk` (D5), which
retired the resolve chunk.

This bounds creation, never the read: the work-source read and the admission stay unbounded, because bounding
the read starves.

An enqueue pass SHALL report the cycle **truncated** exactly when the platform refused a creation
(`LIMIT_EXCEEDED`) — or the settle hit its own cap — because the rows it did not reach still need a job.
Reporting the refusal as an absence of work would publish a completed cycle over a non-empty backlog, leaving
the pump nothing to re-arm on.

#### Scenario: A completion-triggered cycle enqueues from the ledger

- **WHEN** an upload completes, freeing a concurrency slot, and rows needing a job exist in the ledger
- **THEN** the cycle enqueues from those rows, whether or not that cycle's walk found anything new

#### Scenario: A cycle with nothing new to discover still makes progress

- **WHEN** a cycle's walk returns no asset the ledger does not already know, and the ledger holds rows
  needing a job
- **THEN** the cycle enqueues those rows rather than treating a walk with nothing new as no work

#### Scenario: A failed row is retried without re-reading its asset

- **WHEN** a transfer fails and its row is recorded `DISCOVERED`, on a device whose library has not changed
  since
- **THEN** the next cycle re-enqueues that row from the ledger, and its walk does not read that asset's
  resources

#### Scenario: The top-up creates until the platform refuses

- **WHEN** a cycle enqueues more admitted rows than the platform will accept
- **THEN** it resolves and creates row by row, stops the pass at the first `LIMIT_EXCEEDED`, resolves no
  later row, and the work-source read itself stays unbounded

#### Scenario: A refusal truncates rather than reporting no work

- **WHEN** `createJob` answers `LIMIT_EXCEEDED` while admitted rows remain without a job
- **THEN** the cycle is reported truncated and publishes `PROCESSING`, so the trigger's re-arm policy is
  applied to a cycle that knows work remains

#### Scenario: A refusal wastes no resolve

- **WHEN** `createJob` answers `LIMIT_EXCEEDED` for a row
- **THEN** no further row is resolved in that pass, and every row not yet created remains `DISCOVERED` for a
  later cycle

#### Scenario: A backlog the platform accepts is not truncated

- **WHEN** every admitted row's job is created without a refusal
- **THEN** the pass is not reported truncated by the top-up

### Requirement: Ledger keys resolve to uploadable resources

The `UploadDiscovery` port (`:domain` `ports/`) SHALL resolve a set of ledger keys to uploadable resources —
the platform handles `createJob` requires, which a ledger row cannot carry — scoped to those keys and
never by walking the library. It SHALL be bound **once** per composition root to the shared PhotoKit
discovery (`IosDiscovery`), identically on both upload tiers, since both consume the shared cycle; no
transport SHALL implement or forward it. The same port carries the change-token walk, so the two reads the
cycle makes of the library share one binding.

The resolution SHALL be **partial-tolerant**: a key whose asset is no longer in the library resolves to
nothing, and the cycle SHALL treat that as the asset having departed rather than as a failure to
upload. Under a partial photo grant the resolution SHALL be served from the selection snapshot already
in hand, so it performs no library read (capability `limited-photo-access`).

#### Scenario: Keys resolve without a library walk

- **WHEN** the cycle asks `UploadDiscovery` to resolve a set of ledger keys
- **THEN** only those assets' resources are fetched, and nothing else is enumerated

#### Scenario: A departed asset resolves to nothing

- **WHEN** a key's asset has been deleted from the library since its row was recorded
- **THEN** the resolution returns nothing for that key, and the cycle records the asset absent rather
  than reporting an upload failure

#### Scenario: A partial grant resolves from the snapshot

- **WHEN** photo permission is `LIMITED` and the cycle resolves ledger keys
- **THEN** the resolution is served from the current selection snapshot, with no platform read

#### Scenario: One binding serves both tiers

- **WHEN** either tier's composition root assembles its cycle
- **THEN** it binds `IosDiscovery` as the cycle's `UploadDiscovery`, and its transport neither implements
  nor forwards discovery

### Requirement: App-driven upload host on every OS version

On every supported iOS version the **host app process** SHALL perform background uploads whenever its own
admission admits them (photo access `GRANTED` or `LIMITED`, capability `upload-lifecycle`). Below 26.1 it is
the only uploader (there is no app-extension target, because `PHBackgroundResourceUploadExtension` does not
exist below 26.1); on iOS ≥26.1 it uploads **beside** the PhotoKit extension, which the OS may invoke over the
same ledger (capability `ios-photokit-upload`), and neither uploader declines, hands off to, or waits for the
other. Uploads
SHALL run over a background `URLSession` (`URLSessionConfiguration.background`) whose transfers
continue across app suspension and relaunch the app on completion, driven by the same
`feature/upload` `UploadCycle` used by the `ios-photokit-upload` tier (seated in `:domain` by migration step 5). The app SHALL reuse the
existing edge destination contract unchanged: a deterministic per-resource PUT URL built by
`:domain` `model/`'s `EdgeUploadRequestProvider` (seated there by migration step 3a), with `setAssumesHTTP3Capable(false)` applied
to each request (the same HTTP/3-disable workaround the PhotoKit tier requires). Connections SHALL be
HTTPS-only.

Decision record: `changes/both-uploaders-active` (both uploaders are active; nothing hands off, so nothing is
orphaned and nothing needs repair).

#### Scenario: The app is the upload host below 26.1
- **WHEN** the app runs on iOS 18–26.0 with a joined event and full photo access
- **THEN** the app process performs uploads over a background `URLSession` (no extension is invoked), PUTting each resource to its deterministic edge URL

#### Scenario: The app uploads on 26.1+ beside the extension
- **WHEN** the app runs on iOS ≥26.1 with a joined upload-inclusive event and full photo access, and a trigger
  drives its cycle
- **THEN** the app's cycle creates background-`URLSession` jobs for the ledger's `DISCOVERED` rows, while the
  PhotoKit extension stays registered and is not deregistered, withheld, or waited for

### Requirement: The app holds a ledger record-writer on every OS version

On every iOS version the **app process** SHALL hold a `LedgerWriter` over the ledger for its own cycle. Below
26.1 it is the only process that writes the ledger (there is no extension process). On iOS ≥26.1 under a full
grant the extension's cycle holds one too, possibly at the same moment: the ledger's writer rule is **code
ownership** plus guarded one-transaction writes (capability `sync-ledger`), not process exclusivity. The app
SHALL therefore NOT decline, cancel, or repair anything because the other process may be writing.

Overlap is made safe by write-after-act (capability `sync-engine`), not prevented: each cycle picks only
`DISCOVERED` rows and records `REQUESTED` only after `createJob` answered `CREATED`, so two cycles can at worst
each create a job for the same key before either records it — a duplicate upload of identical bytes to the same
destination object, an idempotent PUT — and each completion goes through the guarded `markTerminal`, so the
second of a pair applies to nothing and the row converges. Cross-process write contention SHALL be left to
SQLite's busy timeout (unmeasured, accepted).

Decision record: `changes/both-uploaders-active` (D1, D2).

#### Scenario: App is the sole writer below 26.1
- **WHEN** the app is assembled on iOS 18–26.0
- **THEN** the app constructs the `LedgerWriter` and is the only process touching the ledger

#### Scenario: Both processes hold a writer on 26.1+ under a full grant
- **WHEN** the app is assembled on iOS ≥26.1 under a `GRANTED` grant while the extension is registered
- **THEN** the app constructs its own `LedgerWriter` and its cycles create jobs, although the extension's
  cycle may hold a `LedgerWriter` over the same ledger

#### Scenario: A key one cycle requested is not created again by the next
- **WHEN** one cycle records a key `REQUESTED` and a later cycle, in either process, runs over the same ledger
- **THEN** the later cycle creates no job for that key

