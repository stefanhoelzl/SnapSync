# ios-url-session-upload Specification

## Purpose

The **app-driven upload tier** for iOS 18–26.0: with no PhotoKit upload extension available below 26.1, the
main app process performs uploads itself over a background `URLSession`, pumped by `BGProcessingTask`, driving
the same shared `UploadCycle` as the OS-driven tier.

It exists because the host app deploys to iOS 18 while the extension target is pinned to 26.1, so without it
a sub-26.1 device could join an event and show status but never contribute a single photo. The tier is
selected per OS version at runtime; on this tier the **app** holds the single ledger record-writer, because
no extension process exists to hold it.

The pump necessarily reimplements what the OS gives the other tier for free — scheduling, backpressure,
per-slot temp-file staging, a relaunch drain and a heartbeat — which is why it is the tier that is
**simulator-testable end-to-end** (a background `URLSession` runs in the simulator), even though
`BGProcessingTask` timing and true-suspend behavior remain device-only.

See `ios-photokit-upload` for the OS-driven tier on iOS ≥26.1.

Decision record: `changes/archive/2026-07-04-add-url-session-upload` (the tier),
`changes/archive/2026-07-12-fix-download-session-lifecycle` (why no lifecycle verb may invalidate the
background session — the `disable` bullet used to instruct exactly that, and the sibling download client
that followed it aborted in production).

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
- **re-provision** (a valid `snapsync://` config for a **different** event; re-confirming the
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

- **WHEN** a new valid `snapsync://` config for a different event is scanned on iOS 18–26.0
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

### Requirement: The tier-force flag alters neither transport nor tier exclusivity

The development tier-force flag (`SNAPSYNC_FORCE_URLSESSION_UPLOAD`) SHALL select the **tier** and
nothing else. It SHALL NOT change the transport: uploads SHALL run over a background `URLSession` on a
physical device whether or not the flag is set. Whether the process is running on a **simulator** SHALL
be derived from the environment, never inferred from the tier-force flag.

Forcing the app-driven tier on a device whose OS supports the OS-driven tier SHALL NOT register the
PhotoKit upload extension (`upload-lifecycle`, "Exactly one producer per process"), so the two tiers
are never simultaneously live and the `sync-ledger` single-record-writer invariant holds.

This makes the flag a faithful device-testing lever: it is the only way to exercise the app-driven
tier on a hardware device whose OS is ≥26.1.

#### Scenario: Forcing the tier keeps the background transport

- **WHEN** the app-driven tier is forced on a physical device
- **THEN** uploads run over a background `URLSession`, exactly as they do on a device whose OS selects the tier by version

#### Scenario: Forcing the tier does not enable the extension

- **WHEN** the app-driven tier is forced on a physical device whose OS is ≥26.1
- **THEN** `setUploadJobExtensionEnabled` is never called, only the app-driven producer is live, and exactly one process holds the `LedgerWriter`

