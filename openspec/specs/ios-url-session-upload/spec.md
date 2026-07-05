# ios-url-session-upload Specification

## Purpose
TBD - created by archiving change add-url-session-upload. Update Purpose after archive.
## Requirements
### Requirement: App-driven upload host below iOS 26.1

On iOS versions below 26.1 the **host app process** SHALL perform background uploads (there is no
app-extension target, because `PHBackgroundResourceUploadExtension` does not exist below 26.1). Uploads
SHALL run over a background `URLSession` (`URLSessionConfiguration.background`) whose transfers
continue across app suspension and relaunch the app on completion, driven by the same
`:capability:upload` `UploadCycle` used by the `ios-photokit-upload` tier. The app SHALL reuse the
existing edge destination contract unchanged: a deterministic per-resource PUT URL built by
`:capability:upload-url`'s `EdgeUploadRequestProvider`, with `setAssumesHTTP3Capable(false)` applied
to each request (the same HTTP/3-disable workaround the PhotoKit tier requires). Connections SHALL be
HTTPS-only.

#### Scenario: The app is the upload host below 26.1
- **WHEN** the app runs on iOS 18–26.0 with a joined event and full photo access
- **THEN** the app process performs uploads over a background `URLSession` (no extension is invoked), PUTting each resource to its deterministic edge URL

### Requirement: Per-version tier selection

Tier selection SHALL occur at the existing `backgroundUploadSupported()` guard
(`NSProcessInfo.isOperatingSystemAtLeastVersion(major=26, minor=1, patch=0)`) in the app composition
root (`SnapSyncRoot`). On `true` the app SHALL wire the `ios-photokit-upload` path
(`setUploadJobExtensionEnabled`). On `false` the app SHALL construct and start the app-driven pump
(the `IosUrlSessionUploadPlatform`, the `BackgroundUploadPump`, and the `IosBackgroundScheduler`). The
two tiers SHALL be mutually exclusive within one running process.

#### Scenario: Version gate selects the tier
- **WHEN** `backgroundUploadSupported()` returns false
- **THEN** the composition root starts the app-driven pump and does not call `setUploadJobExtensionEnabled`

#### Scenario: Version gate selects PhotoKit
- **WHEN** `backgroundUploadSupported()` returns true
- **THEN** the composition root registers the PhotoKit extension and does not construct the app-driven pump

### Requirement: App holds the ledger record-writer below 26.1

On iOS 18–26.0 the **app process** SHALL hold the single `LedgerWriter` over the ledger (there is no
extension process). This satisfies the `sync-ledger` single-record-writer invariant with the app as
the writer. Because a single process owns both the record-writes and the reset-family operations,
there SHALL be no cross-process write contention on this tier.

#### Scenario: App is the sole writer below 26.1
- **WHEN** the app is assembled on iOS 18–26.0
- **THEN** the app constructs the `LedgerWriter` and is the only process touching the ledger

### Requirement: Background-URLSession UploadJobPlatform implementation

The app-driven tier SHALL implement the existing `:capability:upload` `UploadJobPlatform` seam with a
background-`URLSession`-backed adapter (`IosUrlSessionUploadPlatform`) — **not** a new seam — so
`UploadCycle` runs unchanged. The adapter SHALL map the seam verbs to `URLSession` semantics:

- `createJob(request, resource)` SHALL start a background `uploadTask(fromFile:)` for the staged
  resource, tag the task with the ledger key via `taskDescription`, and return `CREATED`; when the
  adapter's own in-flight concurrency cap is already reached it SHALL return `LIMIT_EXCEEDED` (the
  adapter's backpressure), and on a failure to start (e.g. unusable staged file) it SHALL return
  `FAILED`.
- `fetchRetryJobs()` SHALL return an **empty** list — this platform grants no OS-sponsored single
  retry; terminal failures are surfaced through `fetchAckJobs()` as retry-spent and recreated by
  `UploadCycle`.
- `fetchAckJobs()` SHALL return terminal jobs recovered from delivered `URLSession` delegate
  completions (success and failure/cancellation), keyed by the task's destination-URL last path
  segment (mirroring the PhotoKit key recovery).
- `retryJob(job, request)` SHALL be implemented as cancel-and-recreate.
- `acknowledge(job)` SHALL perform local cleanup — drop the adapter's task record and delete the
  resource's staged temp file — with no OS acknowledgement (there is no system slot to free).
- `discoverResources(sinceToken)` SHALL reuse the shared change-token walk (`IosDiscovery`), identical
  to the PhotoKit tier.

Correctness SHALL rely on **at-least-once** delivery: keys are deterministic and the edge PUT is
idempotent (a re-PUT overwrites the same object), so a duplicate send is harmless and the ledger
remains the source of truth.

#### Scenario: fetchRetryJobs is empty on this platform
- **WHEN** `UploadCycle` calls `fetchRetryJobs()` on the `URLSession` adapter
- **THEN** it returns an empty list, and a failed upload instead appears via `fetchAckJobs()` as a terminal (retry-spent) job that `UploadCycle` recreates

#### Scenario: Own cap surfaces as LIMIT_EXCEEDED
- **WHEN** `createJob` is called while the adapter already has its cap of tasks in flight
- **THEN** it returns `LIMIT_EXCEEDED`, so `UploadCycle` returns `PROCESSING` and the pump re-arms

#### Scenario: acknowledge cleans up local state
- **WHEN** `acknowledge(job)` is called for a completed job
- **THEN** the adapter drops the task record and deletes the resource's staged temp file, making no OS call

### Requirement: Precise in-flight reconciliation replaces blanket clear

The app-driven tier SHALL reconcile stranded `REQUESTED` rows **precisely** rather than using the
blanket `clearRequested` recovery the PhotoKit tier needs, because a background `URLSession` **can
enumerate** its tasks (`getAllTasks`). At cycle start the adapter SHALL match live tasks to ledger rows by
`taskDescription == key`; a `REQUESTED` row with **no** live task and **not** present in storage SHALL
be surfaced as a terminal `FAILED` job so `UploadCycle` recreates it. The tier SHALL NOT depend on
`clearRequested`.

#### Scenario: Lost task is recreated, survivors untouched
- **WHEN** the app relaunches after the OS dropped a background transfer (e.g. user force-quit) and a `REQUESTED` row has no matching live task and no stored object
- **THEN** that row is surfaced as a terminal failed job and recreated, while `REQUESTED` rows whose tasks are still live remain untouched (the engine's `REQUESTED`-skip holds)

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

The app-driven tier SHALL provide a `BackgroundUploadPump` (in `:capability:upload`, platform-free)
that drives `UploadCycle.run()` — the in-app replacement for the OS-owned `process()` scheduler. The
pump SHALL be invoked by four triggers: (a) app foreground entry, (b) a `BGProcessingTask` handler,
(c) background-`URLSession` completion relaunch (`handleEventsForBackgroundURLSession`), and (d) a
per-upload completion delegate callback. The pump SHALL be **single-flight**: at most one
`UploadCycle.run()` executes at a time; concurrent triggers coalesce into a trailing re-run so no two
cycles write the ledger concurrently. On a `PROCESSING` result the pump SHALL re-arm: in the
foreground it SHALL wait for the next completion (which frees a slot) rather than busy-looping the cap;
in a background context it SHALL ensure the next `BGProcessingTask` is scheduled.

#### Scenario: Concurrent triggers do not run cycles in parallel
- **WHEN** a completion callback and a `BGProcessingTask` fire while a cycle is running
- **THEN** they coalesce; exactly one additional cycle runs after the current one finishes

#### Scenario: PROCESSING on a full cap waits for a completion in foreground
- **WHEN** `UploadCycle.run()` returns `PROCESSING` because the cap is full while foregrounded
- **THEN** the pump does not immediately re-run; the next `URLSession` completion re-invokes it

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
(`scheduleNext()` / `cancel()`) in `:capability:upload`, so the pump's re-arm logic is JVM- and
simulator-testable against a fake. The iOS implementation (`IosBackgroundScheduler`, in
`:app:ios:url-session-upload`) SHALL back it with `BGTaskScheduler`. The genuinely OS-bound wiring —
`BGTaskScheduler` registration, the `URLSession` delegate, and `handleEventsForBackgroundURLSession`
forwarding — SHALL live in the thin, untested Swift shell and forward into the Kotlin pump.

#### Scenario: Re-arm logic is testable without a device
- **WHEN** the pump's re-arm behavior is tested
- **THEN** it runs on JVM and `iosSimulatorArm64` against a fake `BackgroundScheduler` and a fake `UploadCycle`, with no `BGTaskScheduler` dependency

### Requirement: App-driven lifecycle

On iOS 18–26.0 the enable / disable / re-provision / leave lifecycle SHALL be performed by the app
in-process and ordered, with **no** `setUploadJobExtensionEnabled` toggle:

- **enable** (full photo-access grant): run a cycle immediately (foreground) and schedule the first
  `BGProcessingTask`.
- **disable** (access revoked): invalidate/cancel the background `URLSession` and cancel the scheduled
  task.
- **re-provision** (valid `snapsync://` rescan): cancel in-flight tasks for the old event and delete
  their staged temp files, update the `eventId`, reconcile against storage (seed already-stored
  resources as `COMPLETED` via `:capability:rejoin`), then run a cycle — all ordered in one process,
  with no disable→enable toggle and no cross-process race.
- **leave**: cancel all in-flight tasks, cancel the scheduled task, delete staged temp files, then
  clear the ledger and discovery cursor and forget the `eventId` (the platform-neutral leave).

#### Scenario: Re-provision is an in-process ordered sequence
- **WHEN** a new valid `snapsync://` config is scanned on iOS 18–26.0
- **THEN** the app cancels old-event tasks, updates the event, reconciles already-stored resources to `COMPLETED`, and runs a fresh cycle — with no OS toggle and no cross-process timing hazard

#### Scenario: Leave cancels transfers and wipes local state
- **WHEN** the user leaves the event on iOS 18–26.0
- **THEN** in-flight tasks and the scheduled `BGProcessingTask` are cancelled, staged temp files are deleted, and the ledger, discovery cursor, and stored `eventId` are cleared

### Requirement: Module placement and testing split

The app-driven adapters (`IosUrlSessionUploadPlatform`, `IosBackgroundScheduler`) SHALL live in a new,
main-app-composed module `:app:ios:url-session-upload` (not a separate build target), depending on
`:capability:upload` and the shared `:app:ios:photokit-discovery` (`IosDiscovery`). The
`BackgroundUploadPump` and `BackgroundScheduler` SHALL live in `:capability:upload`
(`jvm()`-enabled, harness-covered). The pump and scheduler logic SHALL be tested on JVM and
`iosSimulatorArm64`; the `URLSession` adapter SHALL be faked in the harness (like the PhotoKit
adapter). Because a background `URLSession` runs in the iOS simulator, the transport MAY be exercised
end-to-end in the simulator; `BGProcessingTask` **timing** remains device-only.

#### Scenario: Pump lives in the platform-free capability
- **WHEN** the modules are assembled
- **THEN** `BackgroundUploadPump` and `BackgroundScheduler` are in `:capability:upload`, and the iOS adapters are in `:app:ios:url-session-upload`, which composes `:capability:upload` and `:app:ios:photokit-discovery`

### Requirement: Pump triggers an in-process status refresh after each cycle

On the app-driven tier the upload pump runs **in the main app process**, so it SHALL NOT use the
cross-process Darwin notification (that is the separate-process PhotoKit tier's mechanism). Instead,
after **each** `UploadCycle.run()` the pump SHALL trigger the **same** in-process status refresh the
extension notification triggers on the PhotoKit tier — a re-read of the ledger counts
(`LedgerCountsSource.refresh()`, per `sync-status`) — so foreground upload status moves live without a
cross-process hop. The refresh SHALL be a fire-and-forget side effect that does not alter the pump's
single-flight cycle behavior or its `PROCESSING` re-arm.

#### Scenario: A completed pump cycle refreshes status in-process
- **WHEN** an app-driven `UploadCycle.run()` completes (any result)
- **THEN** the pump triggers the in-process ledger-counts refresh, and issues no cross-process Darwin
  post

#### Scenario: The refresh does not disturb the cycle scheduler
- **WHEN** the in-process refresh runs after a cycle
- **THEN** the pump's single-flight behavior and `PROCESSING` re-arm are unaffected

