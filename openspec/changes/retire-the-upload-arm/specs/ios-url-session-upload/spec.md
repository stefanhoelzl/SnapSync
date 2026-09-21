## MODIFIED Requirements

### Requirement: Per-version tier selection
Upload-mechanism selection SHALL be a pure **resolution**, not a branch in the app composition root, and
the OS fact `backgroundUploadSupported()`
(`NSProcessInfo.isOperatingSystemAtLeastVersion(major=26, minor=1, patch=0)`) SHALL be one of its inputs
(`upload-lifecycle`, "The upload mechanism is resolved, never selected"). Where it is `false` the
app-driven mechanism (the `IosUrlSessionUploadPlatform`, the `BackgroundUploadPump`, and the
`IosBackgroundScheduler`) is the only kind resolution may yield — it is the only mechanism that exists
there, and the OS-driven registration selector does not exist to be called. Where it is `true`,
resolution SHALL yield the PhotoKit kind under `GRANTED` and the app-driven kind under `LIMITED` (capability
`ios-photokit-upload`).

The two mechanisms SHALL be mutually exclusive as ledger writers, and that exclusion SHALL be **gated**: the
app-driven engine's cycle declines as not resolved whenever resolution yields the PhotoKit kind, and the
extension withholds without a `GRANTED` grant (`upload-lifecycle`, "Exactly one mechanism writes the ledger,
enforced at each engine's entry gate").

#### Scenario: Version gate selects the app-driven mechanism below 26.1
- **WHEN** `backgroundUploadSupported()` returns false
- **THEN** resolution yields only the app-driven kind and `setUploadJobExtensionEnabled` is never called

#### Scenario: Full access on 26.1+ runs PhotoKit only
- **WHEN** `backgroundUploadSupported()` returns true and photo access is `GRANTED`
- **THEN** the PhotoKit extension is registered, the app-driven engine is disarmed, and any app-driven cycle a
  trigger drives declines as not resolved

#### Scenario: Limited access on 26.1+ runs the app-driven pump only
- **WHEN** `backgroundUploadSupported()` returns true and photo access is `LIMITED`
- **THEN** the app-driven engine is armed and its cycles run, and an extension invocation withholds

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
- **At a start** — arming the app-driven engine SHALL signal a restart to the cycle, and the next cycle
  to reach its stranded pass SHALL instead record `DISCOVERED` every `REQUESTED` row with **no live transfer**, once;
  later cycles apply the per-cycle rule again. When this engine is armed, no other transport is carrying rows —
  the OS-driven registration has been deregistered, withholds under a partial grant, or does not exist — so a
  `REQUESTED` row without a live task will never be settled. This rule needs no staged file, so it also recovers
  rows whose file is already gone: stranded before the per-cycle rule was scoped, or cancelled by a disarm with no
  completion delivered.

The restart SHALL be consumed **inside the cycle**, never applied by the arm itself. Arming runs outside the
pump's single flight, where a pass reading the live transfers and the `REQUESTED` rows could interleave with a job
creation and demote a transfer that is live. A cycle that does not reach its stranded pass (an unreadable or
absent membership, or a cycle declined as not resolved) SHALL leave the restart pending. The restart is process
state: a process that dies before a cycle consumes it loses it, and the next process's arm — at its launch
reconcile or its next transition — signals it again. A cold background launch arms nothing and so signals no
restart.

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
the join-time ledger load, where the ledger genuinely has no memory — see `join-event`.)

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
- **WHEN** the app-driven engine is armed while `REQUESTED` rows exist with no live task, some with a staged file
  and some without
- **THEN** the next cycle's stranded pass records all of them `DISCOVERED` and leaves rows with a live task untouched,
  and the cycle after it applies the per-cycle rule

#### Scenario: A hand-off from the OS-driven mechanism leaves nothing stranded
- **WHEN** photo access moves from `GRANTED` to `LIMITED` on an OS carrying the OS-driven mechanism while it has
  `REQUESTED` rows
- **THEN** arming the app-driven engine makes the next cycle record those rows `DISCOVERED`, and they are
  re-created by that engine

#### Scenario: A restart is never applied outside a cycle
- **WHEN** the app-driven engine is armed while a cycle is already running
- **THEN** no row is recorded `DISCOVERED` by the arm itself; the restart rule is applied once, by the next cycle to
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

### Requirement: The pump reimplements the OS scheduler

The app-driven tier SHALL provide a `BackgroundUploadPump` (in `:domain` `feature/upload`, platform-free)
that drives `UploadCycle.run()` — the in-app replacement for the OS-owned `process()` scheduler. The
pump SHALL be invoked by six triggers: (a) an arm at a membership transition or launch, (b) app foreground entry, (c) a
`BGProcessingTask` handler, (d) background-`URLSession` completion relaunch
(`handleEventsForBackgroundURLSession`), (e) a per-upload completion delegate callback, and (f) a
**silent push for the active event**. The pump SHALL be **single-flight**: at most one
`UploadCycle.run()` executes at a time; concurrent triggers coalesce into a trailing re-run so no two
cycles write the ledger concurrently. On a `PROCESSING` result the pump SHALL re-arm: in the
foreground it SHALL wait for the next completion (which frees a slot) rather than busy-looping the cap;
in a background context it SHALL ensure the next `BGProcessingTask` is scheduled.

On a `SKIPPED` result — the cycle declined because the membership contributes nothing, or because this
engine is not the resolved mechanism (capability `upload-lifecycle`) — the pump SHALL schedule **nothing**, at
every trigger; the transition that makes the engine eligible again arms it. A non-contributing device
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

### Requirement: App-driven lifecycle
On iOS 18–26.0, and on iOS ≥26.1 under a partial grant, the membership lifecycle SHALL be performed by the app
in-process and ordered, with **no** `setUploadJobExtensionEnabled` toggle on iOS 18–26.0. The **decision** of
which verb fires on which transition belongs to `upload-lifecycle` ("Membership transitions reconcile the upload
mechanisms in one tested place"); this requirement binds the app-driven engine's two verbs:

- **arm** (a join, a reconfigure that enables upload, a permission change, or a launch, whenever resolution
  yields this engine for an upload-inclusive membership): signal a **restart** to the cycle (see "Stranded
  reconciliation: scoped each cycle, complete at a start"), run a cycle immediately, and **schedule the first
  `BGProcessingTask`** (the heartbeat is one-shot, so nothing else would arm it after a force-quit until the next
  foreground).
- **disarm** (a leave, a download-only join, a revocation of usable access, or a transition to the PhotoKit
  mechanism): cancel the in-flight upload **tasks**, delete their staged temp files, and cancel the scheduled
  `BGProcessingTask`. The background `URLSession` itself SHALL be left intact — see "Cancellation never
  invalidates the background session" below. Disarming SHALL NOT clear the ledger and SHALL repair no ledger
  row: a cancelled transfer's `REQUESTED` row is recorded by its own completion when one is delivered, and
  otherwise by the restart repair of the next arm, or by the PhotoKit ritual's demote.
- **switch** (a valid event link for a **different** event; re-confirming the already-joined event is not a
  switch, and neither disarms nor resets the ledger): a leave followed by a join (capabilities
  `upload-lifecycle`, `join-event`). This engine SHALL be **disarmed first** — cancelling the in-flight tasks,
  deleting their staged temp files, and cancelling the scheduled task, with the session left intact — then the
  provision's **join-time load** clears the ledger and re-seeds it from the per-device listing (`resetTo` on a
  successful fetch, `clear()` on a failed one — capability `join-event`), then the new `eventId` is persisted,
  and only then does the join transition arm the engine. The first cycle the arm runs therefore already sees the
  seeded rows, so already-stored resources are `COMPLETED` before any upload job is created; the cycle itself
  seeds nothing and consults no join marker. Cancelling costs at most a re-upload of what was in flight — to the
  same device-partitioned, event-independent destination (`/files/devices/<deviceId>/<filename>`), so it is an
  idempotent overwrite — and whatever landed before the cancel is in the listing the load reads. A completion
  delivered after the load finds no row (or a seeded one) and changes nothing the load did not already account
  for. There SHALL be no disable→enable toggle and no cross-process race.
- **leave**: disarm, then **clear the upload ledger**, then clear the stored `eventId` (the order is
  `LeaveEvent`'s — capability `leave-event`). The clear is the leave's, not the disarm's. The ledger is the
  current membership's share set, so a device that has left holds none; the next join re-seeds it from the
  per-device listing, so nothing already stored re-uploads unless that fetch fails. A completion delivered after
  the clear finds no row, and its outcome is acknowledged and discarded — its bytes are on the backend, where the
  next join's listing finds them. The cycle holds no leave-side action: after the leave it reads the membership
  as absent and uploads nothing.

The four app-side triggers (foreground, silent push, heartbeat, selection change) reach this engine whether or
not it is armed; its cycle's entry gate decides (`upload-lifecycle`, "Triggers are delivered to the mechanism
and declined explicitly").

#### Scenario: Re-provision is an in-process ordered sequence

- **WHEN** a new valid event link for a different event is scanned on iOS 18–26.0
- **THEN** the app disarms the engine, clears the ledger and re-seeds it from the per-device listing, persists the new event, and only then arms the engine — so the first cycle finds already-stored resources `COMPLETED` before any upload job is created, with no OS toggle and no cross-process timing hazard

#### Scenario: A switch cancels in-flight transfers before the ledger is reset

- **WHEN** an event switch occurs while uploads are in flight on iOS 18–26.0
- **THEN** those transfers are cancelled and their staged temp files deleted before the ledger is cleared and re-seeded; what landed before the cancel is seeded `COMPLETED` from the listing, and the rest re-uploads to the same device-partitioned destination

#### Scenario: Arming arms the heartbeat

- **WHEN** the app-driven engine is armed
- **THEN** a restart is signalled to the cycle, a cycle runs, and the first `BGProcessingTask` is submitted

#### Scenario: Disarming preserves the ledger

- **WHEN** the app-driven engine is disarmed (access revoked or a download-only membership)
- **THEN** in-flight tasks and the scheduled `BGProcessingTask` are cancelled, while every ledger row is left intact

#### Scenario: Leave cancels transfers and clears the ledger

- **WHEN** the user leaves the event on iOS 18–26.0
- **THEN** in-flight tasks and the scheduled `BGProcessingTask` are cancelled, then the upload ledger is cleared, then the stored `eventId` is cleared — and joining any event afterwards re-seeds the ledger from the per-device listing, so nothing already in the device's byte partition re-uploads unless that fetch fails

#### Scenario: Disarm cancels tasks without destroying the session
- **WHEN** photo access is revoked on iOS 18–26.0
- **THEN** the in-flight upload tasks and the scheduled `BGProcessingTask` are cancelled and staged temp files deleted, while the background `URLSession` remains valid — so a later re-grant can run a cycle without rebuilding it
