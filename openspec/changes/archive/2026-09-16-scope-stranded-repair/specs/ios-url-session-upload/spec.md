## RENAMED Requirements

- FROM: `### Requirement: Precise in-flight reconciliation replaces blanket clear`
- TO: `### Requirement: Stranded reconciliation: scoped each cycle, complete at a start`

## MODIFIED Requirements

### Requirement: Stranded reconciliation: scoped each cycle, complete at a start

The app-driven tier SHALL record `FAILED` every `REQUESTED` row whose transfer ended without reporting an
outcome — the OS dropped it, or a force-quit or a cancellation ended it with no completion delivered — so a later
cycle re-uploads it: the engine never re-issues a `REQUESTED` key, and nothing else will ever move that row. It
SHALL do this **precisely**, from facts its transport can enumerate, and SHALL NOT depend on any blanket clear. It
SHALL apply two rules, each only where it is true:

- **Each cycle** — immediately after the transport's terminal jobs are drained, the **cycle** SHALL ask the
  transport for the keys of the transfers it has **lost** (see "The transport reports the transfers it still
  holds") and record `FAILED` every `REQUESTED` row among them. A `REQUESTED` row this transport never began —
  on this tier, one it never staged — SHALL NOT be a candidate of this rule, however long it has had no live
  transfer: it may belong to another transport that is still carrying it.
- **At a start** — the app-driven mechanism's `start()` SHALL signal a restart to the cycle, and the next cycle
  to reach its stranded pass SHALL instead record `FAILED` every `REQUESTED` row with **no live transfer**, once;
  later cycles apply the per-cycle rule again. When this mechanism starts, no other transport is carrying rows —
  the OS-driven registration has been relinquished, is inert under a partial grant, or does not exist — so a
  `REQUESTED` row without a live task will never be settled. This rule needs no staged file, so it also recovers
  rows whose file is already gone: stranded before the per-cycle rule was scoped, or cancelled by `stop()` with no
  completion delivered.

The restart SHALL be consumed **inside the cycle**, never applied by `start()` itself. `start()` runs outside the
pump's single flight, where a pass reading the live transfers and the `REQUESTED` rows could interleave with a job
creation and demote a transfer that is live. A cycle that does not reach its stranded pass (an unreadable or
absent membership) SHALL leave the restart pending. The restart is process state: a process that dies before a
cycle consumes it loses it, and the next process's `start()` signals it again.

After either rule the cycle SHALL instruct the transport to `discard` its lost transfers. By then no lost
transfer's row can still be `REQUESTED` — the pass demoted it, or it was not `REQUESTED` to begin with — and no new
transfer is staged until the same single-flight cycle creates jobs, later.

The recovery decision SHALL be the cycle's, not the adapter's. The adapter reports what it holds and reads no
ledger state; the cycle reads the `REQUESTED` keys, selects the candidates from the reported sets, and writes. Placing the rule in
`:domain` `feature/upload` is what makes it testable on every target the module declares rather than only on a
device.

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
re-join reconciliation, where the ledger genuinely has no memory — see `upload-state-reconciliation`.)

A transfer that finishes and leaves the session's task list before its completion is delivered can be demoted by
either rule first; its success then applies to nothing and the photo is uploaded again. That duplicate SHALL be
accepted — the upload is idempotent — rather than guarded against.

#### Scenario: Lost task is recreated, survivors untouched
- **WHEN** the app relaunches after the OS dropped a background transfer (e.g. user force-quit), leaving a `REQUESTED` row whose staged file remains and whose task is gone
- **THEN** that row is recorded `FAILED` and re-uploaded by a later cycle, while `REQUESTED` rows whose tasks are still live remain untouched (the engine's `REQUESTED`-skip holds)

#### Scenario: A row this transport never began is not a per-cycle candidate
- **WHEN** a cycle runs with no restart pending while a `REQUESTED` row has no live task and no staged file
- **THEN** that cycle does not record the row `FAILED`

#### Scenario: A start recovers every row without a live transfer
- **WHEN** the app-driven mechanism starts while `REQUESTED` rows exist with no live task, some with a staged file
  and some without
- **THEN** the next cycle's stranded pass records all of them `FAILED` and leaves rows with a live task untouched,
  and the cycle after it applies the per-cycle rule

#### Scenario: A hand-off from the OS-driven mechanism leaves nothing stranded
- **WHEN** photo access moves from `GRANTED` to `LIMITED` on an OS carrying the OS-driven mechanism while it has
  `REQUESTED` rows
- **THEN** the app-driven mechanism's start makes the next cycle record those rows `FAILED`, and they are
  re-created by that mechanism

#### Scenario: A restart is never applied outside a cycle
- **WHEN** the app-driven mechanism's `start()` runs while a cycle is already running
- **THEN** no row is recorded `FAILED` by `start()` itself; the restart rule is applied once, by the next cycle to
  reach its stranded pass

#### Scenario: Lost transfers are discarded after the pass
- **WHEN** a cycle's stranded pass has run while the transport reports lost transfers
- **THEN** the cycle instructs the transport to discard exactly those transfers, after every candidate write

#### Scenario: An already-adjudicated row is not re-reported
- **WHEN** a cycle runs while the ledger holds a `FAILED` row with no live task
- **THEN** that row is not reported stranded, is not re-written, and produces no loss diagnostic

#### Scenario: A row recorded terminal mid-pass is not overwritten
- **WHEN** the stranded candidates are read while a row is `REQUESTED`, and the delegate records that row
  `COMPLETED` before the pass performs its write
- **THEN** the guarded write applies to nothing and the row remains `COMPLETED`

#### Scenario: The stranded rules are exercised without a device
- **WHEN** the shared cycle runs over a transport double that reports a live set and a lost set, with and without
  a pending restart, while the ledger holds `REQUESTED` rows inside and outside both sets
- **THEN** exactly the rows each rule selects are recorded `FAILED`, on JVM and on `iosSimulatorArm64`

### Requirement: Per-slot temp-file staging

The adapter SHALL stage resource bytes to temp files for background upload (a background `URLSession`
uploads from a file, not from in-memory data). It SHALL materialize a resource's temp file **only when
a concurrency slot frees** (per-slot, bounded by the cap) rather than pre-staging the whole library,
so peak temp-file disk is bounded to a handful of resources. Temp files SHALL live in the shared
App-Group container (the same group as the ledger). Each temp file SHALL be deleted on its task's
terminal completion. A temp file a transfer left behind without completing — a prior process killed
mid-transfer, or one that died between recording an outcome and deleting the file — SHALL be deleted by the
**cycle**, through the transport's `discard`, after the cycle's stranded pass (see "Stranded reconciliation: scoped each cycle, complete at a start"). No temp file SHALL be
deleted while its row may still be `REQUESTED`: the file is what marks a transfer as this transport's, and
deleting it first would hide a lost transfer from the per-cycle pass. Extraction on a retry MAY re-materialize the file. Extraction on a retry MAY re-materialize the file.

#### Scenario: Staging is bounded to open slots
- **WHEN** thousands of resources are pending and the concurrency cap is N
- **THEN** at most ~N resources are materialized to temp files at any time, each deleted on its task's completion

#### Scenario: Orphaned temp files are discarded after the stranded pass
- **WHEN** the app was killed mid-transfer, leaving staged temp files
- **THEN** the next cycle to reach its stranded pass first records those transfers' `REQUESTED` rows `FAILED`,
  then deletes the files

#### Scenario: A start deletes no temp file
- **WHEN** the app-driven mechanism's `start()` runs while staged temp files with no live task exist
- **THEN** no temp file is deleted by the start

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
  background session" below. `stop()` SHALL NOT clear the ledger and SHALL NOT clear the discovery
  cursor. `stop()` SHALL repair no ledger row: a cancelled transfer's `REQUESTED` row
  is recorded by its own completion when one is delivered, and otherwise by the restart repair of whichever
  mechanism starts next (see "Stranded reconciliation: scoped each cycle, complete at a start").
- **re-provision** (a valid event link for a **different** event; re-confirming the
  already-joined event is a no-op that never reaches provisioning): persist the new `eventId` and
  `start()`. In-flight transfers SHALL **NOT** be cancelled and their staged temp files SHALL **NOT**
  be deleted — the byte destination is the device's event-independent partition
  (`/files/devices/<deviceId>/<filename>`), so an in-flight upload remains valid across the switch and
  cancelling it would re-upload identical bytes to an identical URL. The cycle re-reads config each
  run, and its marker-gated reconciliation (`upload-state-reconciliation`) seeds already-stored
  resources as `COMPLETED` and clears the discovery cursor before any upload job is created. There
  SHALL be no disable→enable toggle, no ledger wipe, and no cross-process race.
- **leave**: `stop()` (cancel the in-flight tasks and the scheduled task, leaving the session intact) and
  clear the stored `eventId`. The ledger and the discovery cursor SHALL be **kept** — they are
  device-global dedup state that stays valid across events (`sync-ledger`, "Event-independent key"), and
  clearing them would force a re-upload of every already-stored resource on the next join. The
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

#### Scenario: Stopping preserves the ledger and cursor

- **WHEN** the app-driven producer's `stop()` runs (access revoked or a download-only membership)
- **THEN** in-flight tasks and the scheduled `BGProcessingTask` are cancelled, while every ledger row and the discovery cursor are left intact

#### Scenario: Leave cancels transfers and keeps dedup

- **WHEN** the user leaves the event on iOS 18–26.0
- **THEN** in-flight tasks and the scheduled `BGProcessingTask` are cancelled and the stored `eventId` is cleared, while the ledger and discovery cursor are kept — so joining any event afterwards re-uploads nothing already in the device's byte partition

#### Scenario: Disable cancels tasks without destroying the session
- **WHEN** photo access is revoked on iOS 18–26.0
- **THEN** the in-flight upload tasks and the scheduled `BGProcessingTask` are cancelled and staged temp files deleted, while the background `URLSession` remains valid — so a later re-grant can run a cycle without rebuilding it

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
- `drainTerminals()` SHALL return an **empty** list on this tier and SHALL perform no reconciliation of its
  own. Terminal outcomes are recorded into the ledger by the delegate as they are delivered (see "The delegate
  records the terminal fact before it returns"), so no terminal fact crosses the port, and this tier has
  nothing for the cycle to re-create in-cycle. It SHALL delete the resource's staged temp file when the
  transfer terminates — the file is unusable from that moment, and whatever a killed process leaves is
  discarded by the cycle (see "Per-slot temp-file staging").
- `retryJob(job, request)` SHALL be implemented as cancel-and-recreate.
- `liveKeys()` SHALL report the `taskDescription` of every task the session currently holds (see "The
  transport reports the transfers it still holds").
- `lostKeys()` SHALL report the key of every staged temp file whose key no live task carries, and
  `discard(keys)` SHALL delete exactly those keys' staged temp files (same requirement).

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

#### Scenario: The adapter holds no ledger store and reads no library
- **WHEN** the app-driven adapter is constructed
- **THEN** it is given a `TransferRecord` and no `LedgerStore` or discovery, and the cycle's walk and key
  resolution reach the library only through the root-bound `UploadDiscovery`

### Requirement: The transport reports the transfers it still holds

The `BackgroundTransfer` seam SHALL expose `liveKeys()` — the ledger keys of the transfers the transport
currently holds — or the **absence** of that set for a transport that cannot enumerate them. Both upload tiers
SHALL implement it, since both consume the shared cycle.

It SHALL be a **read the cycle asks for**, never a call from the transport into the core: the transport
decides nothing about the ledger, and the recovery that uses this set runs in the cycle (see "Stranded reconciliation: scoped each cycle, complete at a start").

The app-driven tier SHALL report the `taskDescription` of every task its session holds — the same live set
its concurrency cap and its cancellation already read, so the three cannot disagree.

The OS-driven tier, and any substituted job queue, SHALL report the absence of a set. Its queue is the OS's
durable job store, which exposes exactly two job sets — `.retry` and `.acknowledge` — and no set of jobs still
in flight; a transfer it holds is not lost when the process dies, so it has no stranded population to
reconcile. An absent answer SHALL cause the cycle to run no stranded reconciliation at all, rather than to
treat every `REQUESTED` row as stranded.

The seam SHALL also expose `lostKeys()` — the keys of the transfers the transport **began and no longer
holds**, including those begun by a process that has since died — or the absence of that set, and
`discard(keys)`, which drops whatever the transport kept for those transfers. `lostKeys()` is what lets the
cycle scope its per-cycle pass to this transport's own transfers without the ledger carrying an owner; both are
reads and instructions the cycle issues, and the transport reads no ledger state to answer them.

The app-driven tier SHALL report as lost every key that has a staged temp file and no live task, and `discard`
SHALL delete those keys' staged files. The file is written before the task is created and deleted wherever a
transfer ends inside a live process, so a file with no task is exactly a transfer this transport began and lost.

The OS-driven tier, and any substituted job queue, SHALL report the absence of a lost set and SHALL discard
nothing. An absent lost set SHALL cause the cycle's per-cycle rule to reconcile nothing.

#### Scenario: The app-driven tier reports its live tasks

- **WHEN** the app-driven adapter is asked for its live keys while its session holds tasks
- **THEN** it reports exactly those tasks' `taskDescription` values

#### Scenario: A durable queue reports no set, and nothing is stranded

- **WHEN** the OS-driven adapter is asked for its live keys, and the ledger holds `REQUESTED` rows
- **THEN** it reports the absence of a set, and the cycle records none of those rows `FAILED`

#### Scenario: The app-driven tier reports its lost transfers

- **WHEN** the app-driven adapter is asked for its lost keys while staged files exist for some keys with a live
  task and some without
- **THEN** it reports exactly the keys whose staged file has no live task

#### Scenario: Discard drops only the named transfers

- **WHEN** the cycle instructs the app-driven adapter to discard a set of keys
- **THEN** exactly those keys' staged files are deleted, and a staged file of a live task outside the set remains

#### Scenario: A durable queue reports no lost set

- **WHEN** the OS-driven adapter is asked for its lost keys
- **THEN** it reports the absence of a set, and the cycle's per-cycle rule records nothing `FAILED`
