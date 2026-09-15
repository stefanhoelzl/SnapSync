## MODIFIED Requirements

### Requirement: Precise in-flight reconciliation replaces blanket clear

The app-driven tier SHALL reconcile stranded `REQUESTED` rows **precisely** rather than using the
blanket `clearRequested` recovery the PhotoKit tier needs, because a background `URLSession` **can
enumerate** its tasks (`getAllTasks`). In each cycle, immediately after the transport's terminal jobs are
drained, the **cycle** SHALL ask the transport for the ledger keys of the transfers it still holds (see "The
transport reports the transfers it still holds" — on this tier, the live tasks' `taskDescription`); a row that
is **`REQUESTED`** and has **no** live transfer SHALL be recorded `FAILED` so a later enumeration re-uploads
it. The tier SHALL NOT depend on `clearRequested`.

The recovery decision SHALL be the cycle's, not the adapter's. The adapter reports what it holds and reads no
ledger state; the cycle reads the `REQUESTED` keys, subtracts the reported set, and writes. Placing the rule in
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

#### Scenario: Lost task is recreated, survivors untouched
- **WHEN** the app relaunches after the OS dropped a background transfer (e.g. user force-quit) and a `REQUESTED` row has no matching live task
- **THEN** that row is recorded `FAILED` and re-uploaded by a later enumeration, while `REQUESTED` rows whose tasks are still live remain untouched (the engine's `REQUESTED`-skip holds)

#### Scenario: An already-adjudicated row is not re-reported
- **WHEN** a cycle runs while the ledger holds a `FAILED` row with no live task
- **THEN** that row is not reported stranded, is not re-written, and produces no loss diagnostic

#### Scenario: A row recorded terminal mid-pass is not overwritten
- **WHEN** the stranded candidates are read while a row is `REQUESTED`, and the delegate records that row
  `COMPLETED` before the pass performs its write
- **THEN** the guarded write applies to nothing and the row remains `COMPLETED`

#### Scenario: The stranded rule is exercised without a device
- **WHEN** the shared cycle runs over a transport double that reports a live set, while the ledger holds
  `REQUESTED` rows inside and outside that set
- **THEN** exactly the rows outside it are recorded `FAILED`, on JVM and on `iosSimulatorArm64`

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
  transfer terminates — the file is unusable from that moment, and the launch-time orphan sweep covers
  whatever a killed process leaves.
- `retryJob(job, request)` SHALL be implemented as cancel-and-recreate.
- `liveKeys()` SHALL report the `taskDescription` of every task the session currently holds (see "The
  transport reports the transfers it still holds").

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

### Requirement: The delegate records the terminal fact before it returns

The `URLSession` task-completion delegate SHALL record the terminal outcome into the ledger —
`COMPLETED` on success, `FAILED` otherwise — through the guarded, non-suspending `markTerminal` of the
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
- **THEN** the row is recorded `FAILED`, not `COMPLETED`

#### Scenario: A guarded write that applies to nothing is reported

- **WHEN** the delegate records a terminal outcome for a key whose row is not `REQUESTED`
- **THEN** nothing is written and the outcome is logged, so the un-applied write is visible in a device log

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

## ADDED Requirements

### Requirement: The transport reports the transfers it still holds

The `BackgroundTransfer` seam SHALL expose `liveKeys()` — the ledger keys of the transfers the transport
currently holds — or the **absence** of that set for a transport that cannot enumerate them. Both upload tiers
SHALL implement it, since both consume the shared cycle.

It SHALL be a **read the cycle asks for**, never a call from the transport into the core: the transport
decides nothing about the ledger, and the recovery that uses this set runs in the cycle (see "Precise
in-flight reconciliation replaces blanket clear").

The app-driven tier SHALL report the `taskDescription` of every task its session holds — the same live set
its concurrency cap and its cancellation already read, so the three cannot disagree.

The OS-driven tier, and any substituted job queue, SHALL report the absence of a set. Its queue is the OS's
durable job store, which exposes exactly two job sets — `.retry` and `.acknowledge` — and no set of jobs still
in flight; a transfer it holds is not lost when the process dies, so it has no stranded population to
reconcile. An absent answer SHALL cause the cycle to run no stranded reconciliation at all, rather than to
treat every `REQUESTED` row as stranded.

#### Scenario: The app-driven tier reports its live tasks

- **WHEN** the app-driven adapter is asked for its live keys while its session holds tasks
- **THEN** it reports exactly those tasks' `taskDescription` values

#### Scenario: A durable queue reports no set, and nothing is stranded

- **WHEN** the OS-driven adapter is asked for its live keys, and the ledger holds `REQUESTED` rows
- **THEN** it reports the absence of a set, and the cycle records none of those rows `FAILED`
