## RENAMED Requirements

- FROM: `### Requirement: App-driven upload host below iOS 26.1`
- TO: `### Requirement: App-driven upload host on every OS version`

- FROM: `### Requirement: App holds the ledger record-writer below 26.1`
- TO: `### Requirement: The app holds a ledger record-writer on every OS version`

## MODIFIED Requirements

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
The cycle SHALL walk the admitted rows in **chunks** of a small constant (`resolveChunk`, 4): resolve the
chunk through `UploadDiscovery`, create each resolved row's job, and stop the **whole pass** at the first
`LIMIT_EXCEEDED`. There SHALL be no capacity read and no fixed batch. Resolving a row costs a synchronous
platform round-trip that nothing can interrupt — measured at 11 ms for one key, 19 ms for three and 54 ms for
sixteen against a cap of four (iPhone12,8 / iOS 26.6) — so the chunk bounds what a refusal wastes to at most
`resolveChunk − 1` resolved keys (≈33 ms). A chunk is a granularity, not a cap: both transports refuse
honestly — this tier's `createJob` counts the session's live tasks, so its cap binds across a relaunch, and
PhotoKit refuses at its own job limit. Decision record: `changes/both-uploaders-active` (D9).

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
- **THEN** it resolves and creates chunk by chunk, stops the pass at the first `LIMIT_EXCEEDED`, resolves no
  later chunk, and the work-source read itself stays unbounded

#### Scenario: A refusal truncates rather than reporting no work

- **WHEN** `createJob` answers `LIMIT_EXCEEDED` while admitted rows remain without a job
- **THEN** the cycle is reported truncated and publishes `PROCESSING`, so the trigger's re-arm policy is
  applied to a cycle that knows work remains

#### Scenario: A refusal wastes at most the rest of its chunk

- **WHEN** the refusal lands on the first row of a resolved chunk
- **THEN** at most `resolveChunk − 1` resolved rows go uncreated, and they remain `DISCOVERED` for a later
  cycle

#### Scenario: A backlog the platform accepts is not truncated

- **WHEN** every admitted row's job is created without a refusal
- **THEN** the pass is not reported truncated by the top-up

## REMOVED Requirements

### Requirement: Per-version tier selection
**Reason**: There is no single resolved mechanism any more. Both uploaders are active: the app creates
whenever its own admission admits (`GRANTED` or `LIMITED`) on every OS version, and the extension creates only
under `GRANTED`. The resolver `resolveUploadMechanism` / `UploadMechanism`, and the "mutually exclusive ledger
writers" gate it served, are deleted; what survives is the fact "may the extension be registered"
(`extensionRegistrable`: iOS ≥26.1 and `GRANTED`, never true below 26.1). Decision record:
`changes/both-uploaders-active` (D3, D4).
**Migration**: The per-process admission and `extensionRegistrable` are specified in `upload-lifecycle`; the
extension's registration in `ios-photokit-upload`. This spec's "App-driven upload host on every OS version"
states that the app uploads on every OS.

### Requirement: Stranded reconciliation: scoped each cycle, complete at a start
**Reason**: Nothing orphans a `REQUESTED` row any more. The rows it repaired were orphaned by hand-offs
between uploaders (a disarm's cancel, a deregistration, a demote); with both uploaders active and only the
leave cancelling — whose ledger is cleared anyway — there is nothing to repair. A transfer the OS loses with no
completion is accepted (never observed; a force-quit was measured to deliver `-999` at relaunch, which the
guarded write maps to `DISCOVERED`). Decision record: `changes/both-uploaders-active`.
**Migration**: `strandedEachCycle`, `strandedAtStart`, `signalRestart` and the cycle's stranded pass are
deleted with no replacement. If a silently lost transfer is ever observed, the recovery is a start-time rule on
this transport.

### Requirement: The platform reports the capacity it will accept
**Reason**: The capacity read was a guess the platform does not need to be asked: both transports refuse
honestly with `LIMIT_EXCEEDED`, and the cycle now creates until refused. Decision record:
`changes/both-uploaders-active` (D9).
**Migration**: `BackgroundTransfer.remainingCapacity` and `enqueueBatchSize` are deleted; the chunked
create-until-refused top-up is specified in "The producer tops up from the ledger, not from the walk's output".

### Requirement: The transport reports the transfers it still holds
**Reason**: `liveKeys`, `lostKeys` and `discard` existed only to feed the stranded reconciliation, which is
removed. Decision record: `changes/both-uploaders-active` (D10).
**Migration**: The three members are deleted from `BackgroundTransfer` and every implementation. Staged files
of transfers that vanished with no completion are accepted residue ("Per-slot temp-file staging").
