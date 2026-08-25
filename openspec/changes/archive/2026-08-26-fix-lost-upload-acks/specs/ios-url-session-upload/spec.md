## ADDED Requirements

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

## MODIFIED Requirements

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
