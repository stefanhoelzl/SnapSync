# ios-url-session-upload Specification

## Purpose

The **app-driven upload tier** for iOS 18–26.0: with no PhotoKit upload extension available below 26.1, the
main app process performs uploads itself over a `URLSession` — a **background** one on every shipped binary —
pumped by `BGProcessingTask`, driving the same shared `UploadCycle` as the OS-driven tier.

It exists because the host app deploys to iOS 18 while the extension target is pinned to 26.1, so without it
a sub-26.1 device could join an event and show status but never contribute a single photo. The tier is
selected per OS version at runtime; on this tier the **app** holds the single ledger record-writer, because
no extension process exists to hold it.

The pump necessarily reimplements what the OS gives the other tier for free — scheduling, backpressure,
per-slot temp-file staging, a relaunch drain and a heartbeat — which is why its **logic** is
simulator-testable against a fake transport. The **transport** is simulator-testable too, but only over the
simulator target's own binding: a background `URLSession` transfers nothing on that host, so
`iosSimulatorArm64` binds a default session instead and bytes really move (see "The transport binding is
fixed by the compilation target", which also enumerates what such a run does **not** evidence). What stays
device-only is everything that depends on outliving the process — suspension survival, OS relaunch, task
reattachment — plus `BGProcessingTask` timing.

See `ios-photokit-upload` for the OS-driven tier on iOS ≥26.1.

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

### Requirement: App-driven upload host below iOS 26.1

On iOS versions below 26.1 the **host app process** SHALL perform background uploads (there is no
app-extension target, because `PHBackgroundResourceUploadExtension` does not exist below 26.1). Uploads
SHALL run over a background `URLSession` (`URLSessionConfiguration.background`) whose transfers
continue across app suspension and relaunch the app on completion, driven by the same
`feature/upload` `UploadCycle` used by the `ios-photokit-upload` tier (seated in `:domain` by migration step 5). The app SHALL reuse the
existing edge destination contract unchanged: a deterministic per-resource PUT URL built by
`:domain` `model/`'s `EdgeUploadRequestProvider` (seated there by migration step 3a), with `setAssumesHTTP3Capable(false)` applied
to each request (the same HTTP/3-disable workaround the PhotoKit tier requires). Connections SHALL be
HTTPS-only.

#### Scenario: The app is the upload host below 26.1
- **WHEN** the app runs on iOS 18–26.0 with a joined event and full photo access
- **THEN** the app process performs uploads over a background `URLSession` (no extension is invoked), PUTting each resource to its deterministic edge URL

### Requirement: Per-version tier selection

Upload-mechanism selection SHALL be a pure **resolution**, not a branch in the app composition root, and
the OS fact `backgroundUploadSupported()`
(`NSProcessInfo.isOperatingSystemAtLeastVersion(major=26, minor=1, patch=0)`) SHALL be one of its inputs
(`upload-lifecycle`, "The upload mechanism is resolved, never selected"). Where it is `false` the
app-driven mechanism (the `IosUrlSessionUploadPlatform`, the `BackgroundUploadPump`, and the
`IosBackgroundScheduler`) is the only kind resolution may yield — it is the only mechanism that exists
there, and the OS-driven registration selector does not exist to be called. Where it is `true`,
resolution SHALL yield the PhotoKit kind under `GRANTED` and the app-driven kind under `LIMITED` (the OS
never invokes the extension under a partial grant — capability `ios-photokit-upload`), and the app-driven
kind resolved on such an OS SHALL relinquish any surviving OS-driven registration before it pumps.

The two mechanisms SHALL be mutually exclusive within one running process, and that exclusion SHALL be
**structural**: the orchestrator holds one producer reference, so two cannot be started
(`upload-lifecycle`, "Exactly one producer started per process").

#### Scenario: Version gate selects the app-driven mechanism below 26.1
- **WHEN** `backgroundUploadSupported()` returns false
- **THEN** resolution yields only the app-driven kind and `setUploadJobExtensionEnabled` is never called

#### Scenario: Full access on 26.1+ runs PhotoKit only
- **WHEN** `backgroundUploadSupported()` returns true and photo access is `GRANTED`
- **THEN** the PhotoKit extension is registered and the app-driven pump is not started

#### Scenario: Limited access on 26.1+ runs the app-driven pump only
- **WHEN** `backgroundUploadSupported()` returns true and photo access is `LIMITED`
- **THEN** the app-driven pump is started and the PhotoKit extension is deregistered rather than merely
  left unregistered

### Requirement: App holds the ledger record-writer below 26.1

On iOS 18–26.0 the **app process** SHALL hold the single `LedgerWriter` over the ledger (there is no
extension process). This satisfies the `sync-ledger` single-record-writer invariant with the app as
the writer. Because a single process owns both the record-writes and the reset-family operations,
there SHALL be no cross-process write contention on this tier.

#### Scenario: App is the sole writer below 26.1
- **WHEN** the app is assembled on iOS 18–26.0
- **THEN** the app constructs the `LedgerWriter` and is the only process touching the ledger

### Requirement: Precise in-flight reconciliation replaces blanket clear

The app-driven tier SHALL reconcile stranded `REQUESTED` rows **precisely** rather than using the
blanket `clearRequested` recovery the PhotoKit tier needs, because a background `URLSession` **can
enumerate** its tasks (`getAllTasks`). At cycle start the adapter SHALL match live tasks to ledger rows by
`taskDescription == key`; a row that is **`REQUESTED`**, has **no** live task, and had **no** terminal
outcome delivered this round SHALL be recorded `FAILED` so a later enumeration re-uploads it. The tier SHALL
NOT depend on `clearRequested`.

The candidate set SHALL be the **`REQUESTED`** rows, never the whole non-done backlog. A `FAILED` row has
already been adjudicated; re-surfacing it every cycle re-writes the row, signals a change, and reports a
loss that did not happen — which is what a device log then shows dozens of times for one key inside a single
process.

That write SHALL go through the same guarded `markTerminal` the delegate uses (`sync-ledger`), so a row that
was recorded terminal between this pass's read and its write is never overwritten. The candidate set is read
before the write and the two are not atomic; the guard, not the read, is what makes the write safe.

Storage SHALL NOT be consulted to decide whether a stranded row's bytes landed. That check existed to
compensate for a terminal outcome that was not durably recorded; with the outcome recorded when the platform
delivers it, the remaining stranded population is transfers the OS dropped or a force-quit cancelled — for
which no completion is delivered and the bytes did not land — so the check would pay a full per-device
listing to be told so. A re-upload is idempotent and cheaper. (The device listing remains the seed for
re-join reconciliation, where the ledger genuinely has no memory — see `event-rejoin-reconciliation`.)

#### Scenario: Lost task is recreated, survivors untouched
- **WHEN** the app relaunches after the OS dropped a background transfer (e.g. user force-quit) and a `REQUESTED` row has no matching live task
- **THEN** that row is recorded `FAILED` and re-uploaded by a later enumeration, while `REQUESTED` rows whose tasks are still live remain untouched (the engine's `REQUESTED`-skip holds)

#### Scenario: An already-adjudicated row is not re-reported
- **WHEN** a cycle runs while the ledger holds a `FAILED` row with no live task
- **THEN** that row is not reported stranded, is not re-written, and produces no loss diagnostic

#### Scenario: A row recorded terminal mid-pass is not overwritten
- **WHEN** the stranded candidates are read while a row is `REQUESTED`, and the delegate records that row
  `UPLOADED` before the pass performs its write
- **THEN** the guarded write applies to nothing and the row remains `UPLOADED`

### Requirement: Per-slot temp-file staging

The adapter SHALL stage resource bytes to temp files for background upload (a background `URLSession`
uploads from a file, not from in-memory data). It SHALL materialize a resource's temp file **only when
a concurrency slot frees** (per-slot, bounded by the cap) rather than pre-staging the whole library,
so peak temp-file disk is bounded to a handful of resources. Temp files SHALL live in the shared
App-Group container (the same group as the ledger). Each temp file SHALL be deleted on its task's
terminal completion, and the app SHALL perform an **orphan sweep** on launch to delete temp files left
by a prior killed process. Extraction on a retry MAY re-materialize the file.

#### Scenario: Staging is bounded to open slots
- **WHEN** thousands of resources are pending and the concurrency cap is N
- **THEN** at most ~N resources are materialized to temp files at any time, each deleted on its task's completion

#### Scenario: Orphaned temp files are swept on launch
- **WHEN** the app was killed mid-transfer, leaving staged temp files
- **THEN** on next launch the app deletes the orphaned staging files

### Requirement: The pump reimplements the OS scheduler

The app-driven tier SHALL provide a `BackgroundUploadPump` (in `:domain` `feature/upload`, platform-free)
that drives `UploadCycle.run()` — the in-app replacement for the OS-owned `process()` scheduler. The
pump SHALL be invoked by six triggers: (a) producer start, (b) app foreground entry, (c) a
`BGProcessingTask` handler, (d) background-`URLSession` completion relaunch
(`handleEventsForBackgroundURLSession`), (e) a per-upload completion delegate callback, and (f) a
**silent push for the active event**. The pump SHALL be **single-flight**: at most one
`UploadCycle.run()` executes at a time; concurrent triggers coalesce into a trailing re-run so no two
cycles write the ledger concurrently. On a `PROCESSING` result the pump SHALL re-arm: in the
foreground it SHALL wait for the next completion (which frees a slot) rather than busy-looping the cap;
in a background context it SHALL ensure the next `BGProcessingTask` is scheduled.

On a `SKIPPED` result — the cycle declined because the membership contributes nothing (capability
`upload-lifecycle`) — the pump SHALL schedule **nothing**, at every trigger. A non-contributing device
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
cycle cut short by suspension advances no discovery cursor, so the next wake simply redoes it.

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
On iOS 18–26.0 the enable / disable / re-provision / leave lifecycle SHALL be performed by the app
in-process and ordered, with **no** `setUploadJobExtensionEnabled` toggle. The **decision** of which
verb fires on which transition belongs to `upload-lifecycle`; this requirement binds the app-driven
producer's **mechanism**:

- **`start()`** (the enable verb — a full photo-access grant, a provision, or a re-provision): sweep
  orphaned staging temp files, run a cycle immediately, and **schedule the first `BGProcessingTask`**
  (the heartbeat is one-shot, so nothing else would arm it).
- **`stop()`** (the disable verb — access revoked, or a download-only membership): cancel the in-flight
  upload **tasks**, delete their staged temp files, and cancel the scheduled `BGProcessingTask`. The
  background `URLSession` itself SHALL be left intact — see "Cancellation never invalidates the
  background session" below. `stop()` SHALL NOT clear the ledger and SHALL NOT clear the discovery
  cursor. No blanket `clearRequested` recovery is needed: stranded `REQUESTED` rows are already
  reconciled precisely from `getAllTasks` (see "Precise in-flight reconciliation replaces blanket
  clear").
- **re-provision** (a valid event link for a **different** event; re-confirming the
  already-joined event is a no-op that never reaches provisioning): persist the new `eventId` and
  `start()`. In-flight transfers SHALL **NOT** be cancelled and their staged temp files SHALL **NOT**
  be deleted — the byte destination is the device's event-independent partition
  (`/files/devices/<deviceId>/<filename>`), so an in-flight upload remains valid across the switch and
  cancelling it would re-upload identical bytes to an identical URL. The cycle re-reads config each
  run, and its marker-gated reconciliation (`event-rejoin-reconciliation`) seeds already-stored
  resources as `COMPLETED` and clears the discovery cursor before any upload job is created. There
  SHALL be no disable→enable toggle, no ledger wipe, and no cross-process race.
- **leave**: `stop()` (cancel the in-flight tasks and the scheduled task, leaving the session intact) and
  clear the stored `eventId`. The ledger and the discovery cursor SHALL be **kept** — they are
  device-global dedup state that stays valid across events (`sync-ledger`, "Event-independent key"), and
  clearing them would force a re-upload of every already-stored resource on the next join. The
  `joinedEventId` marker is cleared by the reconciliation gate on the next cycle
  (`event-rejoin-reconciliation`).

#### Scenario: Re-provision is an in-process ordered sequence

- **WHEN** a new valid event link for a different event is scanned on iOS 18–26.0
- **THEN** the app persists the new event and runs a cycle whose reconciliation seeds already-stored resources to `COMPLETED` before any upload job is created — with no OS toggle, no ledger wipe, and no cross-process timing hazard

#### Scenario: Re-provision does not cancel in-flight transfers

- **WHEN** an event switch occurs while uploads are in flight on iOS 18–26.0
- **THEN** those transfers are left running and their staged temp files are retained, because their destination URL is device-partitioned and event-independent and so remains valid after the switch

#### Scenario: Enabling arms the heartbeat

- **WHEN** the app-driven producer's `start()` runs
- **THEN** orphaned staging files are swept, a cycle runs, and the first `BGProcessingTask` is submitted

#### Scenario: Stopping preserves the ledger and cursor

- **WHEN** the app-driven producer's `stop()` runs (access revoked or a download-only membership)
- **THEN** in-flight tasks and the scheduled `BGProcessingTask` are cancelled, while every ledger row and the discovery cursor are left intact

#### Scenario: Leave cancels transfers and keeps dedup

- **WHEN** the user leaves the event on iOS 18–26.0
- **THEN** in-flight tasks and the scheduled `BGProcessingTask` are cancelled and the stored `eventId` is cleared, while the ledger and discovery cursor are kept — so joining any event afterwards re-uploads nothing already in the device's byte partition

#### Scenario: Disable cancels tasks without destroying the session
- **WHEN** photo access is revoked on iOS 18–26.0
- **THEN** the in-flight upload tasks and the scheduled `BGProcessingTask` are cancelled and staged temp files deleted, while the background `URLSession` remains valid — so a later re-grant can run a cycle without rebuilding it

### Requirement: Cancellation never invalidates the background session

Every lifecycle verb that stops transfers SHALL cancel the individual `URLSession` **tasks**, and none of
them — **disable**, **re-provision**, **leave**, or **switch** — SHALL invalidate the background
`URLSession`.

A background `URLSession` is a process-lifetime singleton. Invalidation is **terminal**: creating a task on
an invalidated session throws an Objective-C `NSException`, which Kotlin/Native cannot catch and which
aborts the process. Because every one of these verbs is followed by a later upload — a re-grant after
disable, a fresh cycle after re-provision, a new event after switch — a session destroyed as a means of
cancelling is a crash awaiting the next cycle. Invalidation is reserved for process teardown or for
deliberately discarding a session to rotate its identifier, and is used for neither here. The session
identifier SHALL remain stable so `handleEventsForBackgroundURLSession` can re-adopt it across launches.

This requirement records the rule the tier already implements, and removes the previous instruction to
"invalidate/cancel the background `URLSession`" on disable — which, if implemented literally, would abort
the app on the next upload after a revoke→re-grant. The same rule governs the download client
(`photo-download`), where following that instruction did abort the app in production.

#### Scenario: Re-grant after a disable uploads without a crash

- **WHEN** photo access is revoked (cancelling transfers) and later granted again, and a cycle runs
- **THEN** upload tasks are created on the still-valid background session and the app does not abort

#### Scenario: No lifecycle verb invalidates the session

- **WHEN** disable, re-provision, leave, or switch stops in-flight transfers
- **THEN** each cancels the individual upload tasks and the background `URLSession` remains valid and
  reusable

### Requirement: Module placement and testing split

The app-driven adapters (`IosUrlSessionUploadPlatform`, `IosBackgroundScheduler`) SHALL live in the
app-only adapter module `:adapter:ios:app-only` — linked only by the main app process, never the
extension (before migration step 4 they lived in `:app:ios:url-session-upload`, deleted by that
step) — depending on the extension-safe adapter module `:adapter:ios:ext-safe` for the shared
`IosDiscovery` walk. The
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
reconciliation, no `joinedEventId` marker clear, no discovery-cursor reset, no upload job. The exposure is
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
- **THEN** the cycle skips, the `joinedEventId` marker is intact, and the device is still joined on the
  next readable cycle

#### Scenario: An unresolvable device identity skips rather than throwing
- **WHEN** the app-driven tier runs a cycle and the device identity cannot be resolved
- **THEN** the cycle skips cleanly and no error escapes the cycle

#### Scenario: A definitely-absent membership still clears the marker on this tier
- **WHEN** the app-driven tier runs a cycle after a leave, and the membership read reports no item
- **THEN** the leave-side reconciliation runs and the `joinedEventId` marker is cleared

#### Scenario: The tier's cycle is the shared composition
- **WHEN** `UrlSessionUploadController` assembles its upload cycle
- **THEN** it calls `uploadCore` over its ports — it constructs no gate, cycle, reconciler, or
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
  `LIMIT_EXCEEDED` (the adapter's backpressure), and on a failure to start (e.g. unusable staged file) it
  SHALL return `FAILED`.
- `fetchRetryJobs()` SHALL return an **empty** list — this platform grants no OS-sponsored single
  retry; a terminal failure is recorded `FAILED` by the delegate and re-uploaded from a later enumeration.
- `drainTerminals()` SHALL return an **empty** list on this tier. Terminal outcomes are recorded into the
  ledger by the delegate as they are delivered (see "The delegate records the terminal fact before it
  returns"), so no terminal fact crosses the port, and this tier has nothing for the cycle to re-create
  in-cycle. It SHALL delete the resource's staged temp file when the transfer terminates — the file is
  unusable from that moment, and the launch-time orphan sweep covers whatever a killed process leaves.
- `retryJob(job, request)` SHALL be implemented as cancel-and-recreate.
- `discoverResources(sinceToken)` SHALL reuse the shared change-token walk (`IosDiscovery`), identical
  to the PhotoKit tier.

Correctness SHALL rely on **at-least-once** delivery: keys are deterministic and the edge PUT is
idempotent (a re-PUT overwrites the same object), so a duplicate send is harmless. At-least-once bounds what
a **duplicate** costs; it SHALL NOT be read as licence to lose a delivered outcome. A terminal fact the
platform delivers once is recorded once, durably, and losing it costs a re-upload plus a status that trails
reality until the re-upload completes — which is a defect, not an accepted consequence of at-least-once.

#### Scenario: fetchRetryJobs is empty on this platform
- **WHEN** `UploadCycle` calls `fetchRetryJobs()` on the `URLSession` adapter
- **THEN** it returns an empty list, and a failed upload is instead recorded `FAILED` by the delegate and re-uploaded from a later enumeration

#### Scenario: drainTerminals is empty on this platform
- **WHEN** `UploadCycle` calls `drainTerminals()` on the `URLSession` adapter
- **THEN** it returns an empty list, because every terminal outcome has already been recorded into the ledger

#### Scenario: A recreated upload keeps its original content type
- **WHEN** a `FAILED` key is re-uploaded from a later enumeration
- **THEN** the request carries the content type recorded on its ledger row, so the stored object
  is typed identically on this tier and on the PhotoKit tier

#### Scenario: Own cap surfaces as LIMIT_EXCEEDED
- **WHEN** `createJob` is called while the session already holds the cap of live tasks
- **THEN** it returns `LIMIT_EXCEEDED`, so `UploadCycle` returns `PROCESSING` and the pump re-arms

#### Scenario: A terminated transfer's staged file is deleted
- **WHEN** a task reaches a terminal outcome
- **THEN** the resource's staged temp file is deleted, making no OS call

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

Wherever the app-driven tier is selected on a device whose OS supports the OS-driven tier, the PhotoKit
upload extension SHALL NOT be registered (`upload-lifecycle`, "Exactly one producer per process"), so the two
tiers are never simultaneously live and the `sync-ledger` single-record-writer invariant holds.

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

#### Scenario: The app-driven tier does not enable the extension

- **WHEN** the app-driven tier is live on a device whose OS is ≥26.1 — because the photo grant is partial, or
  because a later runtime selection chose it
- **THEN** `setUploadJobExtensionEnabled(true)` is not called for that producer, only the app-driven producer
  is live, and exactly one process holds the `LedgerWriter`

### Requirement: The delegate records the terminal fact before it returns

The `URLSession` task-completion delegate SHALL record the terminal outcome into the ledger —
`UPLOADED` on success, `FAILED` otherwise — through the guarded, non-suspending `markTerminal`
(`sync-ledger`), **synchronously, before the callback returns**. It SHALL NOT hold the outcome in process
memory for a later cycle to collect.

The forcing fact: iOS delivers a background-`URLSession` completion **once**.
`URLSessionTask.State.completed` is documented as *"the task has completed (without being canceled), and the
task's delegate receives no further callbacks"*, and `handleEventsForBackgroundURLSession` delivers the
events *"waiting to be processed"* — a queue of undelivered events, drained once. No API returns a
completion already delivered, and re-adopting the session by identifier re-delivers only what is still
pending. A fact held in memory across process death is therefore unrecoverable, and the row stays
`REQUESTED` with no live task, which this tier reads as lost and re-uploads.
**Expiry trigger:** the next iOS major, or a device log showing a delivered completion for a task created in
an earlier process.

Synchrony is required, not incidental: after the callback returns the app's continued runtime is not
guaranteed, so work merely scheduled at that point races the system's willingness to keep running the
process. A write whose guard applies to no row SHALL be logged and SHALL NOT be silent.

#### Scenario: A completion survives process death

- **WHEN** an upload completes, its delegate callback returns, and the process is killed before any cycle
  runs
- **THEN** the next process reads the row as `UPLOADED`, and no upload job is created for that key

#### Scenario: The recorded state distinguishes success from failure

- **WHEN** a task completes with a transport error or a non-2xx status
- **THEN** the row is recorded `FAILED`, not `UPLOADED`

#### Scenario: A guarded write that applies to nothing is reported

- **WHEN** the delegate records a terminal outcome for a key whose row is not `REQUESTED`
- **THEN** nothing is written and the outcome is logged, so the un-applied write is visible in a device log

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

A cycle SHALL still consult the change feed, because that is the only way to learn what the library
did — there is no cheaper oracle, and the cursor is not one: `discoverResources(token)` **is** the
question. What changes is the cost of asking. Because the cursor now advances once the walk's facts are
durable, that consultation is an incremental change-token fetch rather than a full enumeration.

This is what makes the tier's concurrency cap a throughput bound rather than an architectural one.
Before it, the only source of work was the walk's return value, so freeing one slot cost a full library
enumeration to refill it: measured on device (build 0.3(605), iPhone11,2 / iOS 18.7.9), 6.1–7.2 seconds
of PhotoKit XPC over 224 candidates to enqueue two to four resources, repeated 26 times in two hours
without ever draining. (That per-walk figure is situational, not intrinsic: the same operation
measured 145 ms for 1084 candidates on an idle iPhone12,8 / iOS 26.6. What the requirement rests on
is the **repetition**, not the cost of any one walk.)

#### Scenario: A completion-triggered cycle enqueues from the ledger

- **WHEN** an upload completes, freeing a concurrency slot, and rows needing a job exist in the ledger
- **THEN** the cycle enqueues from those rows, whether or not that cycle's change feed reported anything

#### Scenario: A cycle with nothing new to discover still makes progress

- **WHEN** a cycle's change feed reports no change and the ledger holds rows needing a job
- **THEN** the cycle enqueues those rows rather than treating an empty change set as no work

#### Scenario: A failed row is retried on a device whose cursor is settled

- **WHEN** a transfer fails and its row is recorded `FAILED`, on a device whose discovery cursor is
  settled and whose library has not changed since
- **THEN** the next cycle re-enqueues that row from the ledger, without waiting for a full enumeration
  to re-derive it

### Requirement: Ledger keys resolve to uploadable resources

The adapter SHALL expose a seam that resolves a set of ledger keys to uploadable resources — the
platform handles `createJob` requires, which a ledger row cannot carry — scoped to those keys and
never by walking the library. Both upload tiers SHALL implement it, since both consume the shared
cycle.

The resolution SHALL be **partial-tolerant**: a key whose asset is no longer in the library resolves to
nothing, and the cycle SHALL treat that as the asset having departed rather than as a failure to
upload. Under a partial photo grant the resolution SHALL be served from the selection snapshot already
in hand, so it performs no library read (capability `limited-photo-access`).

#### Scenario: Keys resolve without a library walk

- **WHEN** the cycle asks the adapter to resolve a set of ledger keys
- **THEN** the adapter fetches only those assets' resources, and enumerates nothing else

#### Scenario: A departed asset resolves to nothing

- **WHEN** a key's asset has been deleted from the library since its row was recorded
- **THEN** the resolution returns nothing for that key, and the cycle records the asset absent rather
  than reporting an upload failure

#### Scenario: A partial grant resolves from the snapshot

- **WHEN** photo permission is `LIMITED` and the cycle resolves ledger keys
- **THEN** the resolution is served from the current selection snapshot, with no platform read
