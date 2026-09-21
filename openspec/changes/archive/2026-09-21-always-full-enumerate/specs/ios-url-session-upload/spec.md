## MODIFIED Requirements

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

### Requirement: App-driven lifecycle
On iOS 18–26.0 the enable / disable / re-provision / leave lifecycle SHALL be performed by the app
in-process and ordered, with **no** `setUploadJobExtensionEnabled` toggle. The **decision** of which
verb fires on which transition belongs to `upload-lifecycle`; this requirement binds the app-driven
producer's **mechanism**:

- **`start()`** (the enable verb — a full photo-access grant, a provision, or a re-provision): signal a
  **restart** to the cycle (see "Stranded reconciliation: scoped each cycle, complete at a start"), run a cycle immediately, and **schedule the first `BGProcessingTask`**
  (the heartbeat is one-shot, so nothing else would arm it).
- **`stop()`** (the disable verb — access revoked, or a download-only membership): cancel the in-flight
  upload **tasks**, delete their staged temp files, and cancel the scheduled `BGProcessingTask`. The
  background `URLSession` itself SHALL be left intact — see "Cancellation never invalidates the
  background session" below. `stop()` SHALL NOT clear the ledger. `stop()` SHALL repair no ledger row: a cancelled transfer's `REQUESTED` row
  is recorded by its own completion when one is delivered, and otherwise by the restart repair of whichever
  mechanism starts next (see "Stranded reconciliation: scoped each cycle, complete at a start").
- **re-provision** (a valid event link for a **different** event; re-confirming the
  already-joined event is a no-op that never reaches provisioning): persist the new `eventId` and
  `start()`. In-flight transfers SHALL **NOT** be cancelled and their staged temp files SHALL **NOT**
  be deleted — the byte destination is the device's event-independent partition
  (`/files/devices/<deviceId>/<filename>`), so an in-flight upload remains valid across the switch and
  cancelling it would re-upload identical bytes to an identical URL. The cycle re-reads config each
  run, and its marker-gated reconciliation (`upload-state-reconciliation`) seeds already-stored
  resources as `COMPLETED` before any upload job is created. There
  SHALL be no disable→enable toggle, no ledger wipe, and no cross-process race.
- **leave**: `stop()` (cancel the in-flight tasks and the scheduled task, leaving the session intact) and
  clear the stored `eventId`. The ledger SHALL be **kept** — it is device-global dedup state that stays
  valid across events (`sync-ledger`, "Event-independent key"), and clearing it would force a re-upload of
  every already-stored resource on the next join. The
  `joinedEventId` marker is cleared by the reconciliation gate on the next cycle
  (`upload-state-reconciliation`).

#### Scenario: Re-provision is an in-process ordered sequence

- **WHEN** a new valid event link for a different event is scanned on iOS 18–26.0
- **THEN** the app persists the new event and runs a cycle whose reconciliation seeds already-stored resources to `COMPLETED` before any upload job is created — with no OS toggle, no ledger wipe, and no cross-process timing hazard

#### Scenario: Re-provision does not cancel in-flight transfers

- **WHEN** an event switch occurs while uploads are in flight on iOS 18–26.0
- **THEN** those transfers are left running and their staged temp files are retained, because their destination URL is device-partitioned and event-independent and so remains valid after the switch

#### Scenario: Enabling arms the heartbeat

- **WHEN** the app-driven producer's `start()` runs
- **THEN** a restart is signalled to the cycle, a cycle runs, and the first `BGProcessingTask` is submitted

#### Scenario: Stopping preserves the ledger

- **WHEN** the app-driven producer's `stop()` runs (access revoked or a download-only membership)
- **THEN** in-flight tasks and the scheduled `BGProcessingTask` are cancelled, while every ledger row is left intact

#### Scenario: Leave cancels transfers and keeps dedup

- **WHEN** the user leaves the event on iOS 18–26.0
- **THEN** in-flight tasks and the scheduled `BGProcessingTask` are cancelled and the stored `eventId` is cleared, while the ledger is kept — so joining any event afterwards re-uploads nothing already in the device's byte partition

#### Scenario: Disable cancels tasks without destroying the session
- **WHEN** photo access is revoked on iOS 18–26.0
- **THEN** the in-flight upload tasks and the scheduled `BGProcessingTask` are cancelled and staged temp files deleted, while the background `URLSession` remains valid — so a later re-grant can run a cycle without rebuilding it

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
reconciliation, no `joinedEventId` marker clear, no upload job. The exposure is
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

What is RESOLVED SHALL be bounded by **what the platform will accept right now**, not by a fixed
batch. A platform that knows its own capacity SHALL report it (see "The platform reports the capacity
it will accept"); where it reports a number, the cycle SHALL take no more admitted rows than that.
Resolving a row costs a synchronous platform round-trip that nothing can interrupt, so every admitted
row taken beyond what the platform will accept is uninterruptible time spent on a job that is not
created — measured at 54 ms for sixteen keys against a cap of four (iPhone12,8 / iOS 26.6), where one
key costs 11 ms and three cost 19 ms. Where the platform reports no number, the fixed batch SHALL
remain that bound.

This bounds the **slice of admitted rows**, never the read, for the reason stated above: bounding the
read starves, and the capacity is applied after the admission for the same reason the batch is.

An enqueue pass that **leaves admitted rows it could not take** SHALL report the cycle
**truncated**, because those rows still need a job. Bounding the read removes the
signal that previously carried this: truncation was observed by the platform refusing a creation, and a
pass that never asks for more than the platform will accept is never refused. Without it, every
capacity below the backlog would publish a drained cycle over remaining work.

An enqueue pass that resolves nothing **because the platform reports no free capacity** SHALL likewise
report the cycle **truncated**, not drained. The platform being full while rows still need a job is
backpressure — the same fact the platform's own limit signal carries — and reporting it as an absence
of work would publish a completed cycle over a non-empty backlog, leaving the pump nothing to re-arm
on.

#### Scenario: A completion-triggered cycle enqueues from the ledger

- **WHEN** an upload completes, freeing a concurrency slot, and rows needing a job exist in the ledger
- **THEN** the cycle enqueues from those rows, whether or not that cycle's walk found anything new

#### Scenario: A cycle with nothing new to discover still makes progress

- **WHEN** a cycle's walk returns no asset the ledger does not already know, and the ledger holds rows
  needing a job
- **THEN** the cycle enqueues those rows rather than treating a walk with nothing new as no work

#### Scenario: A failed row is retried without re-reading its asset

- **WHEN** a transfer fails and its row is recorded `FAILED`, on a device whose library has not changed
  since
- **THEN** the next cycle re-enqueues that row from the ledger, and its walk does not read that asset's
  resources

#### Scenario: The top-up asks for no more than the platform will take

- **WHEN** a cycle enqueues while the platform reports free capacity smaller than the fixed batch
- **THEN** the slice of admitted rows is bounded by that capacity, so no row is resolved for a job the
  platform would refuse, and the work-source read itself stays unbounded

#### Scenario: A full platform truncates rather than reporting no work

- **WHEN** a cycle enqueues while the platform reports zero free capacity and rows needing a job
  remain in the ledger
- **THEN** no row is resolved, the cycle is reported truncated, and it publishes `PROCESSING` so the
  trigger's re-arm policy is applied to a cycle that knows work remains

#### Scenario: A saturated read reports work remaining

- **WHEN** a cycle enqueues, the admitted rows outnumber what the platform will take, and every row it
  does take is accepted
- **THEN** the cycle is reported truncated, so a backlog larger than one pass is never published as a
  drained cycle

#### Scenario: A platform that reports no capacity keeps the fixed batch

- **WHEN** a cycle enqueues on a platform that reports no free-capacity number
- **THEN** the fixed batch bounds the read exactly as before
