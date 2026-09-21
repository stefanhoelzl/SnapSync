## MODIFIED Requirements

### Requirement: Stranded reconciliation: scoped each cycle, complete at a start

The app-driven tier SHALL record `DISCOVERED` every `REQUESTED` row whose transfer ended without reporting an
outcome — the OS dropped it, or a force-quit or a cancellation ended it with no completion delivered — so a later
cycle re-uploads it: the engine never re-issues a `REQUESTED` key, and nothing else will ever move that row. It
SHALL do this **precisely**, from facts its transport can enumerate, and SHALL NOT depend on any blanket clear. It
SHALL apply two rules, each only where it is true:

- **Each cycle** — immediately after the transport's terminal jobs are drained, the **cycle** SHALL ask the
  transport for the keys of the transfers it has **lost** (see "The transport reports the transfers it still
  holds") and record `DISCOVERED` every `REQUESTED` row among them. A `REQUESTED` row this transport never began —
  on this tier, one it never staged — SHALL NOT be a candidate of this rule, however long it has had no live
  transfer: it may belong to another transport that is still carrying it.
- **At a start** — the app-driven mechanism's `start()` SHALL signal a restart to the cycle, and the next cycle
  to reach its stranded pass SHALL instead record `DISCOVERED` every `REQUESTED` row with **no live transfer**, once;
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

The candidate set SHALL be the **`REQUESTED`** rows, never the whole non-done backlog. A row that needs a job
has already been returned to the ledger's work read; re-surfacing it every cycle re-writes the row, signals a
change, and reports a loss that did not happen — which is what a device log then shows dozens of times for one key inside a single
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
- **THEN** that row is recorded `DISCOVERED` and re-uploaded by a later cycle, while `REQUESTED` rows whose tasks are still live remain untouched (the engine's `REQUESTED`-skip holds)

#### Scenario: A row this transport never began is not a per-cycle candidate
- **WHEN** a cycle runs with no restart pending while a `REQUESTED` row has no live task and no staged file
- **THEN** that cycle does not record the row `DISCOVERED`

#### Scenario: A start recovers every row without a live transfer
- **WHEN** the app-driven mechanism starts while `REQUESTED` rows exist with no live task, some with a staged file
  and some without
- **THEN** the next cycle's stranded pass records all of them `DISCOVERED` and leaves rows with a live task untouched,
  and the cycle after it applies the per-cycle rule

#### Scenario: A hand-off from the OS-driven mechanism leaves nothing stranded
- **WHEN** photo access moves from `GRANTED` to `LIMITED` on an OS carrying the OS-driven mechanism while it has
  `REQUESTED` rows
- **THEN** the app-driven mechanism's start makes the next cycle record those rows `DISCOVERED`, and they are
  re-created by that mechanism

#### Scenario: A restart is never applied outside a cycle
- **WHEN** the app-driven mechanism's `start()` runs while a cycle is already running
- **THEN** no row is recorded `DISCOVERED` by `start()` itself; the restart rule is applied once, by the next cycle to
  reach its stranded pass

#### Scenario: Lost transfers are discarded after the pass
- **WHEN** a cycle's stranded pass has run while the transport reports lost transfers
- **THEN** the cycle instructs the transport to discard exactly those transfers, after every candidate write

#### Scenario: An already-adjudicated row is not re-reported
- **WHEN** a cycle runs while the ledger holds a `DISCOVERED` row, returned there by an earlier failure, with no
  live task
- **THEN** that row is not reported stranded, is not re-written, and produces no loss diagnostic

#### Scenario: A row recorded terminal mid-pass is not overwritten
- **WHEN** the stranded candidates are read while a row is `REQUESTED`, and the delegate records that row
  `COMPLETED` before the pass performs its write
- **THEN** the guarded write applies to nothing and the row remains `COMPLETED`

#### Scenario: The stranded rules are exercised without a device
- **WHEN** the shared cycle runs over a transport double that reports a live set and a lost set, with and without
  a pending restart, while the ledger holds `REQUESTED` rows inside and outside both sets
- **THEN** exactly the rows each rule selects are recorded `DISCOVERED`, on JVM and on `iosSimulatorArm64`

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
- **THEN** the next cycle to reach its stranded pass first records those transfers' `REQUESTED` rows `DISCOVERED`,
  then deletes the files

#### Scenario: A start deletes no temp file
- **WHEN** the app-driven mechanism's `start()` runs while staged temp files with no live task exist
- **THEN** no temp file is deleted by the start

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
  retry; a terminal failure is recorded `DISCOVERED` by the delegate and re-uploaded from a later enumeration.
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
- **THEN** it returns `LIMIT_EXCEEDED`, so `UploadCycle` returns `PROCESSING` and the pump re-arms

#### Scenario: A terminated transfer's staged file is deleted
- **WHEN** a task reaches a terminal outcome
- **THEN** the resource's staged temp file is deleted, making no OS call

#### Scenario: The adapter holds no ledger store and reads no library
- **WHEN** the app-driven adapter is constructed
- **THEN** it is given a `TransferRecord` and no `LedgerStore` or discovery, and the cycle's walk and key
  resolution reach the library only through the root-bound `UploadDiscovery`

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
`REQUESTED` with no live task, which this tier reads as lost and re-uploads.
**Expiry trigger:** the next iOS major, or a device log showing a delivered completion for a task created in
an earlier process.

Synchrony is required, not incidental: after the callback returns the app's continued runtime is not
guaranteed, so work merely scheduled at that point races the system's willingness to keep running the
process. A write whose guard applies to no row SHALL be logged and SHALL NOT be silent.

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

- **WHEN** a transfer fails and its row is recorded `DISCOVERED`, on a device whose library has not changed
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
- **THEN** it reports the absence of a set, and the cycle records none of those rows `DISCOVERED`

#### Scenario: The app-driven tier reports its lost transfers

- **WHEN** the app-driven adapter is asked for its lost keys while staged files exist for some keys with a live
  task and some without
- **THEN** it reports exactly the keys whose staged file has no live task

#### Scenario: Discard drops only the named transfers

- **WHEN** the cycle instructs the app-driven adapter to discard a set of keys
- **THEN** exactly those keys' staged files are deleted, and a staged file of a live task outside the set remains

#### Scenario: A durable queue reports no lost set

- **WHEN** the OS-driven adapter is asked for its lost keys
- **THEN** it reports the absence of a set, and the cycle's per-cycle rule records nothing `DISCOVERED`
