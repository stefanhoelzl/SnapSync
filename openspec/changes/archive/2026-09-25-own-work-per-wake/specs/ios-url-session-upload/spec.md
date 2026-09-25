## MODIFIED Requirements

### Requirement: The tail runner reimplements the OS scheduler

The app-driven tier's upload work SHALL be driven by the app process's **tail runner** — the in-app replacement
for the OS-owned `process()` scheduler, and the successor of the retired `BackgroundUploadPump`. What the tail is,
its unit order (① import staged downloads, ② upload **top-up**, ③ discovery walk → manifest publish under a full
grant only, looping to ② when ③ added rows), its single-flight admission, and how it stops, are
`ios-app-shell`'s ("Each OS wake does its own work, then hands the rest to one opportunistic tail", "Expiry stops
work cooperatively at the next boundary"; decision record `changes/own-work-per-wake`, design D1). This
requirement states what this tier adds: which triggers reach the tail's upload units, how a request is never
lost, and when the heartbeat is re-armed. On this tier the top-up is "The producer tops up from the ledger, not
from the walk's output".

The tail's upload units are reached from these triggers, each with its own work before the tail:

- (a) an **arm** at a membership transition or launch — no own work; the tail runs;
- (b) **foreground entry** — own work: the download reconcile, the stored-upload settle (capability
  `upload-state-reconciliation`), the status refresh and the membership refresh — no upload unit among them;
  then the tail in full, through the same runner — foreground has no second, concurrent upload path;
- (c) the **`BGProcessingTask` heartbeat** — no own work beyond the prelude: the task runs the tail (① import,
  ② top-up, ③ walk → manifest publish) under its own grant of time;
- (d) a **background-`URLSession` relaunch** (`handleEventsForBackgroundURLSession`) — own work: the transport
  records the delivered terminals (see "The delegate records the terminal fact before it returns"); then the
  tail;
- (e) a per-upload **completion** — which requests **② only**, never a walk, and **only when the app's admission
  is `Admit`** (see "The delegate records the terminal fact before it returns");
- (f) a **silent push for the active event** — own work: the download reconcile (capability `photo-download`);
  then the tail;
- (g) a **selection change** under a partial grant — own work: the snapshot-fed discovery → manifest publish
  (capability `limited-photo-access`); then the tail;
- (h) a **download-session relaunch** — own work: staging the delivered files (capability `photo-download`);
  then the tail;
- (i) a **download staged in a running process** — own work: recording the staging; then **① only** — it
  reaches no upload unit, and a pass that ran none says nothing about uploads (below).

The uploader these units belong to is a **mechanism**: it offers the top-up, the walk → manifest publish, its
heartbeat, the leave's transfer cancellation and the session's reattach, and holds **no trigger and no OS
completion handler**. Which wake runs what, how a wake's handler is held and when the heartbeat is re-armed are
the core's — the tail runner and the inbound port's implementation — so a mechanism can neither fail to release a
handler nor run a unit the tail did not ask for. What its transport observes — a recorded completion, the
session's report that it delivered every event — reaches the core as one call each, and the upload session's
handlers are held and released by the core (capability `ios-app-shell`).

A completion requests ② alone for the reason `ios-app-shell` records (a freed slot is all it changes; design
D2) — on this tier that is what ended a full library walk per freed slot.

No two of this process's upload units SHALL write the ledger concurrently (the extension's cycle, a separate
process, may overlap one — see "The app holds a ledger record-writer on every OS version"). The tail is
single-flight, but a selection change's own work runs its walk **outside** the runner by design, so the exclusion
SHALL be the upload cycle's own: it serialises its units — a whole cycle, a top-up, a walk → manifest — whoever
calls them, rather than relying on a caller's convention. **A request that arrives while the tail runs SHALL NOT be lost.** Joining a tail that has already
passed the unit a request needs — a completion's top-up arriving while the tail walks, say — would otherwise
drop it; the running tail SHALL therefore make one more pass covering the units the requests that joined it
need — the union of their units, however many arrived, coalesced into that one pass — and the decision to end
the tail and the clearing of its running state SHALL be one atomic step, so a request can never slip between "decide to stop" and "no tail
is running". This carries over the retired pump's trailing re-run; it extends the running tail and never queues
a second one.

**A truncated top-up never busy-loops.** When ② ends because the platform refused a creation
(`LIMIT_EXCEEDED` — the cap is full), the tail SHALL NOT re-run ② for that reason alone: in the foreground the
next completion frees a slot and requests ②; in a background context the re-armed heartbeat wakes the app. The
③ → ② loop runs only because ③ recorded new rows, and it runs ② once more — never ③ again.

**A failed unit fails the tail.** A unit that throws SHALL end the whole tail: every caller awaiting it is failed
(see "A wake that joins a running tail keeps its obligations"), and the pass joiners requested of it is consumed
with it. A stop requested while no tail runs SHALL be a no-op.

**Re-arm.** After its tail, each trigger SHALL decide whether to schedule the next `BGProcessingTask` from the
**outcome** of the tail's upload units, as a `CycleResult`, with its own policy:

- the **arm**, **foreground entry**, a **silent push**, a **selection change**, and the **heartbeat** SHALL
  always schedule the next task (the heartbeat's re-submission is what keeps it alive; the others are the moments
  the chain may have been severed — see "Foreground entry re-arms the heartbeat" and "A silent push drives an
  upload scan");
- a **background-`URLSession` relaunch** — of the upload session and of the download session alike — SHALL
  schedule the next task **only when work remains** (`PROCESSING`): a background wake whose tail left upload work
  re-arms the heartbeat, one that finished does not;
- a **completion** SHALL schedule nothing: while the app is open, completions re-invoke the top-up;
- a **download staged in a running process** SHALL schedule nothing: the wake or the foreground it arrived in owns
  the heartbeat's re-arm.

The tail's outcome SHALL be computed over the upload results of its **latest pass that ran an upload unit**: a
pass that ran no upload unit (an import alone) keeps the previous one's, and a tail that never ran one reports
`COMPLETED`. Over those results, the latest unit's `SKIPPED` wins — the freshest gate answer says the membership
contributes nothing; otherwise a tail cut short by a stop, or with any unit that left work, is `PROCESSING`; then
any `FAILED` is `FAILED`; otherwise `COMPLETED`.

A tail that Apple's expiry signal stopped before its upload units finished — a `BGTask`'s `expirationHandler`
or the expiry of the background task a push or `URLSession` wake began (capability `ios-app-shell`) — SHALL be
treated as leaving work (`PROCESSING`) for this decision, at every trigger, never as `COMPLETED`: a stop says
nothing about whether work remains, and the only safe reading is that it does. A relaunch whose tail was cut
short therefore still re-arms. The one exception is an outcome the units had already reached as `SKIPPED`
before the stop, which re-arms nothing (below). Because the expiry is answered at once (the handler released,
the task completed, the background task ended), the stopped tail reaches its end — and this decision — only once
the unit in flight completes, which may be when the process next runs rather than before it is suspended.

On a `SKIPPED` outcome — the upload units declined because the membership contributes nothing (its selection
policy admits nothing, as for a download-only membership), or because there is no membership at all
(capability `upload-lifecycle`) — the tail runner SHALL schedule **nothing**, at every trigger, including the
heartbeat whose re-submission is otherwise unconditional; the transition that makes the engine eligible again
arms it. Every app-side trigger reaches this engine whatever its state, so an unjoined device's foreground would
otherwise submit a self-re-submitting heartbeat for no event. A non-contributing device SHALL therefore hold no
`BGProcessingTask`, and SHALL stop waking once any outstanding one fires. Re-arming a device that will never
upload would wake it forever to do nothing.

The re-arm decision SHALL be expressed over the `CycleResult` variants exhaustively, so a future variant cannot
silently inherit a re-arm policy nobody chose for it, and it SHALL be made outside the runner's admission lock.

#### Scenario: Concurrent triggers do not run tails in parallel
- **WHEN** a completion callback and a `BGProcessingTask` fire while a tail is running
- **THEN** both join the running tail, no second tail starts, and exactly one further pass covering their units
  runs after the current one finishes

#### Scenario: A completion tops up without walking
- **WHEN** an upload completes while the app's admission is `Admit`
- **THEN** the tail runs ② only, creating jobs from the ledger, and no discovery walk runs for that completion

#### Scenario: PROCESSING on a full cap waits for a completion in foreground
- **WHEN** the top-up ends `PROCESSING` because the cap is full while foregrounded
- **THEN** the tail does not immediately re-run ②; the next `URLSession` completion requests it

#### Scenario: A walk that adds rows loops to the top-up
- **WHEN** the tail's ③ records new `DISCOVERED` rows
- **THEN** the tail runs ② again, so the new rows get jobs in the same tail

#### Scenario: SKIPPED never re-arms the heartbeat
- **WHEN** the tail's upload units return `SKIPPED` at any trigger, including the `BGProcessingTask` handler
  whose re-arm is otherwise unconditional
- **THEN** no `BGProcessingTask` is scheduled, so the device stops waking to upload

#### Scenario: A completion requests the top-up only while the app may create
- **WHEN** an upload completion is delivered while the app's admission is not `Admit` (e.g. photo access was
  revoked)
- **THEN** the tail runner is not requested for that completion

#### Scenario: A relaunch tail stopped by expiry still re-arms
- **WHEN** a background-`URLSession` relaunch's tail is stopped by Apple's expiry signal before its upload units
  finish
- **THEN** the outcome counts as work remaining, and the next `BGProcessingTask` is scheduled unless the units
  had returned `SKIPPED`

### Requirement: A silent push drives an upload scan

A silent push for the device's **active event** SHALL drive this tier's upload units, and SHALL re-arm the
heartbeat. The `BGProcessingTask` heartbeat is scheduled at the OS's discretion and is routinely deferred well
beyond its `earliestBeginDate`; a silent push is the reliable wake, and it arrives precisely when an event is
live, because it is emitted when another member's device drains a cycle that completed an upload (capability
`upload-completion-notify`).

The push's **own work** is the download reconcile (capability `photo-download`); the upload units — the top-up
and, under a full grant, the discovery walk and manifest publish — run in the **tail** that follows it (see
"The tail runner reimplements the OS scheduler"; decision record `changes/own-work-per-wake`, design D1). A push
arriving while another wake's walk is running SHALL do its download work at once rather than wait behind that
walk (measured: an iPhone XS push waited 22.5 s behind a walk), then join the running tail.

The upload arm is **no longer a receiver** of the push: the `flow/SilentPush` trigger runs the download arm's
receiver alone, and the upload units reach the push's wake only through the tail. The active-event decision —
whether the wake joins the tail — SHALL live in a tested feature (`PushTailGuard`, `:domain` `feature/upload`),
asked by the inbound port's implementation after the flow has returned (spec `module-architecture`, "A trigger
flow never outlives its own run"), and SHALL NOT be duplicated in the composition root. It reads the membership
the flow has just re-read; no event configured, another event, or an **unreadable** membership each join nothing,
and an unreadable one is logged as its own answer. A download arm that fails still leaves the wake its tail.

The active-event guard SHALL be **orthogonal** to the direction gate (capability `upload-lifecycle`): the
active-event guard answers "is this push for my current event", the direction gate answers "should this device
ever upload here". A push for an event that is not the active one SHALL drive no upload unit — notably a
locally-left event, whose backend membership persists (leave is local-only, capability `leave-event`) and which
therefore keeps pushing this device.

The push handler SHALL be released once the push's own work is done and SHALL NOT be held for the upload units
(capability `ios-app-shell`): iOS grants a silent push a short budget with no expiry callback, and a library walk
can exceed it. The upload units run under the app's own background task, which ends at once on its expiry
signal while the units start nothing further (capability `ios-app-shell`, "Expiry stops work cooperatively at the next boundary"); the scan is
therefore best-effort — a walk stopped mid-way is abandoned and writes nothing (`ios-app-shell`, "The discovery
walk is atomic under a stop"), the ledger writes already made are idempotent, and the next wake's walk is a full
enumeration that simply redoes it.

#### Scenario: A push for the active event drives the upload units and re-arms
- **WHEN** a silent push arrives naming the device's active event on a contributing membership
- **THEN** after the download reconcile, the tail runs the upload units and the next `BGProcessingTask` is
  scheduled

#### Scenario: A push for another event drives nothing
- **WHEN** a silent push arrives naming an event that is not the device's active event, including a
  locally-left event the backend still pushes, or while the membership is unreadable
- **THEN** its wake requests no tail, so no upload unit is driven

#### Scenario: A push to a download-only membership passes the active-event guard and still uploads nothing
- **WHEN** a silent push arrives for the active event on a membership whose direction excludes upload
- **THEN** the wake joins the tail (the active-event guard passes), its upload units return `SKIPPED`, so no
  upload job is created and no heartbeat is scheduled

#### Scenario: The push completion handler is not held for the upload units
- **WHEN** a silent push drives the upload units
- **THEN** the OS completion handler is released once the push's own work is done, without waiting for the
  tail to drain

### Requirement: Foreground entry re-arms the heartbeat

App foreground entry SHALL re-arm the `BGProcessingTask` heartbeat for a contributing membership, in
addition to running the tail in full.

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
- **THEN** the upload units return `SKIPPED` and no `BGProcessingTask` is scheduled

### Requirement: Two background engines — relaunch drain and heartbeat

Background progress SHALL be driven by two mechanisms with distinct roles. (1) The **relaunch
ping-pong** — the background `URLSession` continues transfers after suspension and, on completion while
suspended/terminated, iOS relaunches the app via `handleEventsForBackgroundURLSession` — SHALL be the primary
drain. The relaunch's own work is **recording** the delivered terminals, which the delegate does as each is
delivered; its OS handler is released when the session reports its events drained, and the top-up that refills
the freed slots runs in the tail that follows, under the app's own background task (capability
`ios-app-shell`). (2) A **`BGProcessingTask` heartbeat** SHALL be the cold-start / new-photo kick: while the
session is idle (no in-flight task to trigger a relaunch) but pending or newly-captured work exists, the
scheduled task wakes the app. It has **no own work** beyond the shared prelude: the task is a grant of time, and
what it runs is the tail — ① import staged downloads, ② top up, ③ discovery walk → manifest publish (under a full
grant; under a partial grant ① and ② only) and, when ③ recorded new rows, ② again — restarting the ping-pong. The
`BGProcessingTask` SHALL request `requiresNetworkConnectivity = true` and `requiresExternalPower = false`.

The heartbeat handler SHALL hold `setTaskCompleted` until its tail has finished, or until the task's
`expirationHandler` fires — the `BGProcessingTask` is what grants the minutes, so its tail runs under the
task rather than under a separate background task. On expiry the tail's stop SHALL be requested and the task
SHALL be completed **at once**, without waiting for the unit in flight, which runs on until the process is
suspended while nothing new starts; an in-flight walk is abandoned (capability `ios-app-shell`, "Expiry stops
work cooperatively at the next boundary" and "The discovery walk is atomic under a stop"). No deadline of the
app's own SHALL bound the task (`ios-app-shell`, "Time is up is learned only from the operating system"). Each
handler SHALL re-submit the next task while an event remains joined (the request is one-shot) — whether its tail
finished or was stopped by the expiry, in which case the re-submission follows once the stopped tail ends —
subject only to the `SKIPPED` rule of "The tail runner reimplements the OS scheduler". Decision record: `changes/own-work-per-wake` (design D1, D3, D4, D5).

#### Scenario: Completions self-sustain the drain
- **WHEN** background transfers complete while the app is suspended
- **THEN** iOS relaunches the app, the delegate records the completions, the tail tops up the queue, and this
  repeats until discovery is exhausted

#### Scenario: Heartbeat catches new photos when the session is idle
- **WHEN** new photos are captured while the app is closed and no upload is in flight
- **THEN** a `BGProcessingTask` (network-gated, power not required) wakes the app, whose tail walks to discover
  them, publishes the manifest and enqueues them, and the next task is re-submitted

#### Scenario: The heartbeat holds its task until its tail ends or expires
- **WHEN** the heartbeat's tail is still running when the task's `expirationHandler` fires
- **THEN** `setTaskCompleted` is called at once, no further unit starts, and the next task is re-submitted
  once the stopped tail ends; had the tail finished first, the task would have been completed at that moment

### Requirement: BackgroundScheduler seam

Re-arm scheduling SHALL be expressed as a platform-free seam `BackgroundScheduler`
(`scheduleNext()` / `cancel()`), so the tail runner's re-arm logic is JVM- and
simulator-testable against a fake. The iOS implementation (`IosBackgroundScheduler`, in
`:adapter:ios:app-only` — the app-only adapter module; before migration step 4,
`:app:ios:url-session-upload`) SHALL back it with `BGTaskScheduler`. The genuinely OS-bound wiring —
`BGTaskScheduler` registration, the `URLSession` delegate, and `handleEventsForBackgroundURLSession`
forwarding — SHALL live in the thin, untested Swift shell and forward into the Kotlin core.

#### Scenario: Re-arm logic is testable without a device
- **WHEN** the tail runner's re-arm behavior is tested
- **THEN** it runs on JVM and `iosSimulatorArm64` against a fake `BackgroundScheduler` and fake upload units,
  with no `BGTaskScheduler` dependency

### Requirement: App-driven lifecycle
On every iOS version the membership lifecycle of the app-driven engine SHALL be performed by the app
in-process and ordered. The **decision** of
which verb fires on which transition belongs to `upload-lifecycle` ("Membership transitions reconcile the upload
mechanisms in one tested place"); this requirement binds the app-driven engine's three verbs. They are
independent of the PhotoKit extension's registration, which on iOS ≥26.1 spans the membership from join to
leave wherever the OS allows it (capability `ios-photokit-upload`); the app engine is armed beside it.

- **arm** (a join, any reconfigure, a permission change, or a launch, whenever photo access is usable —
  `GRANTED` or `LIMITED`): request the tail at once — **detached**, because a transition runs inside a flow or a
  tap and neither awaits the tail (spec `module-architecture`, "A trigger flow never outlives its own run") — and
  **schedule the first `BGProcessingTask`** from that tail's outcome (the heartbeat is one-shot, so nothing else would arm it after a force-quit until the next
  foreground). Arming repairs no ledger row. It does not read the membership's direction: on a membership that
  contributes nothing the upload units decline on the selection policy and return `SKIPPED`, so the tail runner
  schedules nothing. A re-provision of the already-joined event (`SwitchDecision.Stay`) arms nothing.
- **disarm** (a revocation of usable access, and a leave): cancel the scheduled `BGProcessingTask` — and
  **nothing else**. It SHALL NOT cancel an in-flight transfer, delete a staged temp file, clear the ledger, or
  repair a row. A revocation therefore stops **new** creation (the app's admission withholds) and new wakes (the
  heartbeat); transfers already in flight finish, and their completions are recorded by the delegate's guarded
  write (see "The delegate records the terminal fact before it returns") and request no top-up while the
  admission withholds.
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
  first tail the arm runs therefore already sees the
  seeded rows, so already-stored resources are `COMPLETED` before any upload job is created; the upload units
  seed nothing and consult no join marker. Cancelling costs at most a re-upload of what was in flight — to the
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
  next join's listing finds them. The upload units hold no leave-side action: after the leave they read the
  membership as absent and upload nothing.

The app-side triggers (foreground, silent push, heartbeat, selection change) reach this engine whether or
not it is armed; its upload units' entry gate decides (`upload-lifecycle`, "Triggers are delivered to the
mechanism and declined explicitly").

Decision record: `changes/both-uploaders-active` (D5, D6); `changes/own-work-per-wake` (the tail runner).

#### Scenario: Re-provision is an in-process ordered sequence

- **WHEN** a new valid event link for a different event is scanned
- **THEN** the app runs the leave (disarming the engine and cancelling its transfers), clears the ledger and re-seeds it from the per-device listing, persists the new event, and only then arms the engine — so the first tail finds already-stored resources `COMPLETED` before any upload job is created, with no cross-process timing hazard

#### Scenario: A switch cancels in-flight transfers before the ledger is reset

- **WHEN** an event switch occurs while uploads are in flight
- **THEN** those transfers are cancelled and their staged temp files deleted before the ledger is cleared and re-seeded; what landed before the cancel is seeded `COMPLETED` from the listing, and the rest re-uploads to the same device-partitioned destination

#### Scenario: Re-confirming the joined event does nothing to the engine

- **WHEN** a valid event link for the already-joined event is scanned while uploads are in flight
- **THEN** the engine is neither armed, disarmed, nor cancelled, and the in-flight transfers continue

#### Scenario: Arming arms the heartbeat

- **WHEN** the app-driven engine is armed on a contributing membership
- **THEN** the tail runs, and the first `BGProcessingTask` is submitted

#### Scenario: Arming a download-only membership schedules nothing

- **WHEN** the app-driven engine is armed on a membership whose selection policy admits nothing
- **THEN** the upload units return `SKIPPED`, no upload job is created, and no `BGProcessingTask` is submitted

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

### Requirement: Module placement and testing split

The app-driven adapters (`IosUrlSessionUploadPlatform`, `IosBackgroundScheduler`) SHALL live in the
app-only adapter module `:adapter:ios:app-only` — linked only by the main app process, never the
extension (before migration step 4 they lived in `:app:ios:url-session-upload`, deleted by that
step) — depending on the extension-safe adapter module `:adapter:ios:ext-safe` for the shared
upload-request builder. The shared `IosDiscovery` walk is bound by the app's composition root as the
`UploadDiscovery` port, not held by the adapter. The
tail runner and the `BackgroundScheduler` seam SHALL live in `:domain` — the runner platform-free beside the
upload feature it drives, the scheduler seam in `ports/` (seated by migration step 5; formerly
`:capability:upload`) — `jvm()`-enabled and harness-covered. The tail runner and scheduler logic SHALL be
tested on JVM and
`iosSimulatorArm64`; the `URLSession` adapter SHALL be faked in the harness (like the PhotoKit
adapter). The transport MAY be exercised end-to-end on a simulator, over that target's **default**
session binding (see "The transport binding is fixed by the compilation target") — which evidences the
request, the delegate, the staging move and the outcome path, and evidences **none** of the
background-session properties that requirement enumerates. `BGProcessingTask` **timing** and true-suspend
behaviour remain device-only.

#### Scenario: The tail runner lives in the platform-free core
- **WHEN** the modules are assembled
- **THEN** the tail runner is in `:domain`, and the iOS adapters are in `:adapter:ios:app-only`, which composes `:adapter:ios:ext-safe`; the runner and the `uploadCore`-assembled upload units are composed with the adapters in the app's composition root, not by the adapter module

#### Scenario: A simulator end-to-end run is scoped to what it shows
- **WHEN** the transport is exercised end-to-end on a simulator
- **THEN** the bytes move and the outcome path is exercised, and the run is recorded as evidencing neither
  suspension survival nor OS relaunch

### Requirement: The tail refreshes status in-process only while foregrounded

On the app-driven tier the tail runner SHALL, after **each** of its units — while the app is **foregrounded**,
and only then — trigger an in-process status refresh: a re-read of the ledger counts
(`LedgerCountsSource.refresh()`, per `sync-status`), so foreground upload status moves at unit granularity, not
only at the foreground-gated poll's cadence. While the app is backgrounded the tail SHALL trigger no such
refresh: nothing renders the counts, the read is work a darwinbg-clamped wake can least afford, and foreground
entry re-reads them anyway (decision record `changes/own-work-per-wake`, design D11; it replaces the retired
pump's refresh after every cycle, in either state). (The cross-process Darwin liveness notification this
requirement used to contrast against is deleted on every tier — migration step 12; the poll in `sync-status`
is the cross-process mechanism's replacement, and this in-process refresh stands beside it.) The refresh SHALL
be a fire-and-forget side effect whose failure is logged and does not alter the tail's single-flight behavior,
its unit order, or its re-arm.

#### Scenario: A foreground tail unit refreshes status in-process
- **WHEN** a tail unit completes while the app is foregrounded (any outcome)
- **THEN** the tail runner triggers the in-process ledger-counts refresh, and posts no cross-process
  notification

#### Scenario: A background tail refreshes nothing
- **WHEN** a tail unit completes while the app is backgrounded
- **THEN** no ledger-counts refresh is triggered, and the next foreground entry re-reads the counts

#### Scenario: The refresh does not disturb the tail
- **WHEN** the in-process refresh runs, or fails, after a unit
- **THEN** the tail's single-flight behavior, its unit order and its `PROCESSING` re-arm are unaffected

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

This tier runs its upload units in the app process, from every wake that reaches its tail runner (arm,
foreground, heartbeat, session events, completion, silent push, selection change). Each unit, whichever wake
ran it, SHALL produce **Skip** on an unreadable membership: no ledger write, clear or reset, and no upload job.
The exposure is
narrow — the membership item is stored `AfterFirstUnlock`, so an unreadable read needs a boot with no
unlock — and the requirement stands regardless: the accessibility attribute makes a false leave
improbable, the three-state read makes it impossible.

The tier SHALL probe the device identity per cycle rather than resolving it once into a held value. A held
identity cannot express "unreadable this cycle": an unresolvable identity throws out of whatever first
touches it instead of skipping cleanly. The probe is per-process in effect on both tiers already — the
identity caches for the process lifetime, and the OS-invoked tier's per-cycle probe is per-process because
its process dies each cycle.

#### Scenario: A background task on an unreadable membership does not leave the event
- **WHEN** the app-driven tier runs its heartbeat's work and the membership read fails because
  protected data is unavailable
- **THEN** the units skip, the ledger is untouched, and the device is still joined on the next readable
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
  retry; a terminal failure is recorded `DISCOVERED` by the delegate and re-uploaded by a later top-up.
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
- **THEN** it returns an empty list, and a failed upload is instead recorded `DISCOVERED` by the delegate and re-uploaded by a later top-up

#### Scenario: drainTerminals is empty on this platform
- **WHEN** `UploadCycle` calls `drainTerminals()` on the `URLSession` adapter
- **THEN** it returns an empty list, because every terminal outcome has already been recorded into the ledger

#### Scenario: A recreated upload keeps its original content type
- **WHEN** a key whose upload failed, returning its row to `DISCOVERED`, is re-uploaded by a later top-up
- **THEN** the request carries the content type recorded on its ledger row, so the stored object
  is typed identically on this tier and on the PhotoKit tier

#### Scenario: Own cap surfaces as LIMIT_EXCEEDED
- **WHEN** `createJob` is called while the session already holds the cap of live tasks
- **THEN** it returns `LIMIT_EXCEEDED`, so the top-up stops creating for that pass, reports it truncated,
  returns `PROCESSING`, and the trigger's re-arm policy is applied to it

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
differs under `LIMITED` is **when the library is read**: no autonomous trigger (foreground entry, silent push,
the heartbeat) SHALL initiate a library read while permission is `LIMITED` (capability `limited-photo-access`,
"No autonomous library reads under a limited grant"), so under a partial grant the tail SHALL run ① and ② only
and its unit ③ — the discovery walk and manifest publish — SHALL NOT run. Discovery runs from the cold-launch
baseline and as a **selection change's own work** — the snapshot-fed discovery and manifest publish — and the
tail's top-up (every wake's, a silent push's, completions', session events' and the heartbeat's included) SHALL
drain already-enqueued work without a library read: it resolves rows from the in-memory selection snapshot, and
is withheld while that snapshot is unread.

#### Scenario: A selected photo uploads under limited via the ordinary cycle
- **WHEN** a `LIMITED` member with upload-inclusive direction selects an in-scope photo and the
  selection change's own work records it
- **THEN** the app-driven mechanism uploads it exactly as it would any enqueued work — background
  session, staging, ledger `COMPLETED`, manifest, notify

#### Scenario: Continuation drains without re-reading the library
- **WHEN** several enqueued uploads complete one after another under `LIMITED`
- **THEN** each completion's top-up uploads the remaining queue without initiating a new library read

#### Scenario: A push under limited tops up from the snapshot
- **WHEN** a silent push for the active event arrives while permission is `LIMITED` and `DISCOVERED` rows exist
- **THEN** its tail imports and tops up, resolving the rows from the selection snapshot, and runs no walk and
  no library read

### Requirement: A wake that joins a running tail keeps its obligations

A wake that joins a running tail SHALL NOT be discarded. The tail runner admits one tail at a time, and a
request that arrives while one runs **joins** it: the running tail makes exactly one more pass covering the
units the joiners need, however many joined — no request is lost, no second runner starts, and nothing is
queued ("The tail runner reimplements the OS scheduler"; capability `ios-app-shell`, "Each OS wake does its own
work, then hands the rest to one opportunistic tail"). The joining caller SHALL await that tail and, when it
ends, SHALL apply **its own** trigger's re-arm policy against the `CycleResult` that tail's upload units ended
with.

Returning immediately drops two obligations at once, and both cost the app future background wakes. A caller
that awaited nothing cannot be the work a `BGProcessingTask` or a background task is held for — the task is
ended against a tail still running elsewhere. And a caller that skipped its re-arm leaves the
`BGProcessingTask` chain unarmed, which is fatal because the request is one-shot: the heartbeat then resumes
only when the user next foregrounds the app. Both were measured in the field under the retired pump — the
background-session trigger exited in 0–2 ms on 30 of 30 relaunch wakes while the cycle ran on for seconds, and
a coalesced heartbeat returned in 2 ms and re-submitted nothing.

The re-arm SHALL be evaluated against the tail's outcome rather than assumed, because the joining caller ran
no upload unit of its own and only that outcome answers whether work remains. A `SKIPPED` outcome SHALL
therefore still arm nothing, from any trigger, exactly as for a caller that started the tail.

The awaited span is the running tail **including** the further pass the joining caller requested. No clock of
the app's own SHALL bound that wait: what ends it early is Apple's expiry signal, which stops the tail
cooperatively (capability `ios-app-shell`, "Expiry stops work cooperatively at the next boundary"), never a
cancellation of the tail by the caller. A stop consumes any pass joiners requested, and they receive the stopped
tail's outcome. The OS handler of a push or a background-session relaunch is not held across the wait at all —
it is released after that wake's own work — and a `BGTask`'s completion, like any background time the waiting
wake holds, is released at once on the expiry rather than after the wait.

Where a tail fails, every caller awaiting it SHALL be failed rather than left parked, and the further pass
requested of it SHALL be consumed with it — it belonged to that tail, and leaving it set would arm a phantom
pass on whichever request came next.

A joining caller SHALL never be the tail runner itself: nothing a tail unit or its status refresh does may
request and await the tail, or the join would wait on itself — such a request SHALL be refused loudly (it
throws) rather than deadlock.

#### Scenario: A background-session relaunch that joins still re-arms on its result

- **WHEN** background-session events are delivered while a tail started by a completion is already running
- **THEN** the relaunch records its terminals, joins that tail, awaits it, and applies its own re-arm policy to
  the tail's outcome

#### Scenario: A joining heartbeat still re-submits

- **WHEN** a `BGProcessingTask` handler fires while a tail is already running
- **THEN** it joins the running tail (it has no own work of its own), awaits that tail including the one more
  pass its join requested, holds its task until then, and re-submits the next task, because its trigger's
  re-arm is unconditional

#### Scenario: A joining relaunch trigger re-arms only on remaining work

- **WHEN** a background-session trigger joins and the tail it awaited ends `COMPLETED`
- **THEN** no next task is scheduled; had the tail ended `PROCESSING`, one would be

#### Scenario: A joining trigger against a declining membership arms nothing

- **WHEN** a trigger joins a tail whose upload units end `SKIPPED`
- **THEN** nothing is scheduled, whatever the joining trigger's own policy would otherwise be

#### Scenario: A failed tail releases its waiters

- **WHEN** a tail throws while callers are awaiting it and a further pass has been requested
- **THEN** every awaiting caller is failed rather than left waiting, and the requested pass is not run by the
  next tail on its behalf

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
`handleEventsForBackgroundURLSession` wake on that target never sees the signal its handler is released on:
the core holds that handler until the background time it began at the handover expires, and releases it then
(capability `ios-app-shell`, "OS completion handlers are released only after their work completes";
`architecture-guards`, "OS completion handlers are held in one type") — there is no deadline of the app's own to expire. Measured on the iOS 26.5 simulator (n=5, 2026-09-27): that
expiry fires **27.4–28.5 s after the app enters the background**, never while it is in the foreground (held up to
302 s), and the handler is released on it exactly once — so the simulator's wake is bounded by Apple's background
time, not held forever (decision record `changes/own-work-per-wake`). That outcome SHALL be **predicted rather than diagnosed**: the process
SHALL state its binding and this consequence when the session is constructed, so the expiry line is not read
as a fault. Nothing SHALL synthesise the drain — a transport that reported events drained without the OS having
delivered any would make a simulator run indistinguishable from a device one, which is exactly the false
confidence `fix-download-session-lifecycle` D5 refused.

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

#### Scenario: A background-events wake on a simulator is released on expiry, and says so in advance

- **WHEN** a `handleEventsForBackgroundURLSession` wake is driven on a simulator
- **THEN** the handler is held until the expiry of the background time begun at the handover releases it,
  because the session never reports its events drained; the process has already stated that this binding
  cannot report them, and no drain is synthesised

#### Scenario: The binding is readable, not inferred

- **WHEN** a caller asks a running process which transport binding it holds
- **THEN** it is answered directly, without reading a log line or waiting for a transfer to stall

### Requirement: The delegate records the terminal fact before it returns

The `URLSession` task-completion delegate SHALL record the terminal outcome into the ledger —
`COMPLETED` on success, `DISCOVERED` otherwise (the platform's failed outcome returns the row to the ledger's
work read) — through the guarded, non-suspending `markTerminal` of the
`TransferRecord` it is given (`sync-ledger`), **synchronously, before the callback returns**. It SHALL NOT
hold the outcome in process memory for a later cycle to collect. Success is recorded as the settled state:
nothing a completion used to trigger is still owed, so no later cycle reads or re-settles the row. Recording
these terminals is the **whole own work** of a background-session relaunch (see "Two background engines —
relaunch drain and heartbeat").

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
revocation finishes and records. After recording, the completion SHALL request the tail's **top-up (②) only** —
never a discovery walk — and **only when the app's admission is `Admit`** right now. That decision SHALL live in
the tested tail runner (its completion request takes the current admission), never in the shell, which forwards
every completion. A late completion delivered after a revocation therefore records its row and requests
nothing — the 2026-09-16 field observation was late `-999`s after a hand-off each driving an app cycle.
Decision records: `changes/both-uploaders-active` (D7); `changes/own-work-per-wake` (design D2 — a freed slot
needs the top-up alone).

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
- **THEN** its row is recorded through the guarded write, and no top-up is requested by that completion

#### Scenario: A completion while the app may create tops up

- **WHEN** a transfer completes while the app's admission is `Admit`
- **THEN** its row is recorded and the tail runs its top-up from the ledger, with no discovery walk

### Requirement: The producer tops up from the ledger, not from the walk's output

On this tier the upload **top-up** SHALL enqueue work from the ledger's rows that need a job (capability
`sync-ledger`). It SHALL NOT take its work from the discovery walk's return value: the walk's job is to
**record** what it found, and creating jobs from what it happens to be holding is what made the cycle unable to
resume work it had already seen.

The walk is still the only way to learn what the library holds, and every walk is a full enumeration
(capability `ios-photokit-upload`, "In-extension discovery by full enumeration"; this tier binds the same
`IosDiscovery`). What bounds its cost is that it reads resources only for the assets the ledger does not fully
know (capability `sync-ledger`, "A walk re-reads only the assets the ledger does not fully know"). A walk is
**not** run per completion: a completion requests the top-up alone (see "The delegate records the terminal fact
before it returns"), and the walk runs only as the tail's unit ③ (under a full grant) — or, under a partial grant,
as a selection change's snapshot-fed discovery, which is that wake's own work — so the top-up that refills a freed
slot costs no library enumeration at all (decision record `changes/own-work-per-wake`, design D2).

This is what makes the tier's concurrency cap a throughput bound rather than an architectural one.
Before it, the only source of work was the walk's return value, so freeing one slot cost a full library
enumeration to refill it: measured on device (build 0.3(605), iPhone11,2 / iOS 18.7.9), 6.1–7.2 seconds
of PhotoKit XPC over 224 candidates to enqueue two to four resources, repeated 26 times in two hours
without ever draining. (That per-walk figure is situational, not intrinsic: the same operation
measured 145 ms for 1084 candidates on an idle iPhone12,8 / iOS 26.6. What the requirement rests on
is the **repetition**, not the cost of any one walk.) With the ledger as the work source no refill depends on
the walk, and with completions requesting the top-up alone no refill repeats it either.

What is CREATED SHALL be bounded only by **the platform's own refusal**, never by a guess at its capacity.
The top-up SHALL walk the admitted rows **one at a time**: obtain the row's resource, create its job, and stop
the **whole pass** at the first `LIMIT_EXCEEDED`, before the next row's resource is obtained. There SHALL be no
capacity read, no fixed batch and no resolve chunk. Both transports refuse honestly: this tier's `createJob`
counts the session's live tasks, so its cap binds across a relaunch, and PhotoKit refuses at its own job limit.

**A row the walk just read is created from the walk's resource.** The tail's walk creates no job itself: it
records what it found, publishes the manifest, and hands the resources it read to the one top-up that follows it
in the same tail, which consumes them. Where the walk that immediately precedes a top-up in the same run read a
row's asset, the row's job SHALL be created from the resource that walk already
holds, keyed by the ledger key — one platform read of the same asset moments earlier, so a second synchronous
round-trip would buy nothing but its cost. Only a **miss** — a row that walk did not read: a retried failure, a
truncated pass's remainder whose asset the ledger already fully knows, or any row of a top-up no walk preceded —
SHALL be resolved by id through `UploadDiscovery`, and only a miss there is evidence the asset is gone. The shape
is unchanged: one row at a time, stopping at the first refusal before the next row is looked up. (Wording sync
of a behaviour-preserving change: decision record `changes/own-work-per-wake`, design D13.)

Resolving a row by id costs a synchronous platform round-trip that nothing can interrupt, measured at **~4.5 ms
per request plus ~3.45 ms per photo** (rig probe, SE2 / iOS 26.6, 2026-09-22; 100 distinct images per run, two
rounds plus a warm repeat, no cache effect). For 100 photos that is 0.80–0.94 s one at a time, against
0.46 s in fours and 0.37 s in sixteens. The difference is accepted for simplicity, and it is paid only for a
miss: a row the preceding walk read costs no resolve. It applies only under a full grant: under a partial grant
keys resolve from the selection snapshot already in hand, with no platform call (see "Ledger keys resolve to
uploadable resources"). The earlier figure of "11 ms for one key" understated the per-photo cost this call
carries.

Decision records: `changes/both-uploaders-active` (D9), and `changes/selection-is-the-walk` (D5), which
retired the resolve chunk.

This bounds creation, never the read: the work-source read and the admission stay unbounded, because bounding
the read starves.

A top-up SHALL report its pass **truncated** exactly when the platform refused a creation
(`LIMIT_EXCEEDED`) — or the settle hit its own cap — because the rows it did not reach still need a job.
Reporting the refusal as an absence of work would publish a completed outcome over a non-empty backlog, leaving
the tail runner nothing to re-arm on.

#### Scenario: A completion-triggered top-up enqueues from the ledger

- **WHEN** an upload completes, freeing a concurrency slot, and rows needing a job exist in the ledger
- **THEN** the top-up enqueues from those rows, and no walk runs for that completion

#### Scenario: A walk with nothing new to discover still makes progress

- **WHEN** a walk returns no asset the ledger does not already know, and the ledger holds rows needing a job
- **THEN** the top-up enqueues those rows rather than treating a walk with nothing new as no work

#### Scenario: A failed row is retried without re-reading its asset

- **WHEN** a transfer fails and its row is recorded `DISCOVERED`, on a device whose library has not changed
  since
- **THEN** a later top-up re-enqueues that row from the ledger, and no walk reads that asset's resources

#### Scenario: A row the walk just read is not resolved again

- **WHEN** a walk records a new `DISCOVERED` row and the top-up that follows it in the same run creates that
  row's job
- **THEN** the job is created from the resource the walk already holds, with no resolve by id for that row

#### Scenario: The top-up creates until the platform refuses

- **WHEN** a top-up enqueues more admitted rows than the platform will accept
- **THEN** it obtains and creates row by row, stops the pass at the first `LIMIT_EXCEEDED`, resolves no
  later row, and the work-source read itself stays unbounded

#### Scenario: A refusal truncates rather than reporting no work

- **WHEN** `createJob` answers `LIMIT_EXCEEDED` while admitted rows remain without a job
- **THEN** the pass is reported truncated and publishes `PROCESSING`, so the trigger's re-arm policy is
  applied to an outcome that knows work remains

#### Scenario: A refusal wastes no resolve

- **WHEN** `createJob` answers `LIMIT_EXCEEDED` for a row
- **THEN** no further row is resolved in that pass, and every row not yet created remains `DISCOVERED` for a
  later top-up

#### Scenario: A backlog the platform accepts is not truncated

- **WHEN** every admitted row's job is created without a refusal
- **THEN** the pass is not reported truncated by the top-up

## RENAMED Requirements

- FROM: `### Requirement: The pump reimplements the OS scheduler`
- TO: `### Requirement: The tail runner reimplements the OS scheduler`

- FROM: `### Requirement: Pump triggers an in-process status refresh after each cycle`
- TO: `### Requirement: The tail refreshes status in-process only while foregrounded`

- FROM: `### Requirement: A coalesced pump trigger keeps its obligations`
- TO: `### Requirement: A wake that joins a running tail keeps its obligations`
