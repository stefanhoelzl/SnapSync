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
- **re-provision** (a valid event link for a **different** event — a switch; re-confirming the
  already-joined event is not a switch, and neither stops the tier nor resets the ledger): a switch is a
  leave followed by a join (capabilities `upload-lifecycle`, `join-event`). This tier SHALL be
  **`stop()`ped first** — cancelling the in-flight tasks, deleting their staged temp files, and cancelling
  the scheduled task, with the session left intact — then the provision's **join-time load** clears the
  ledger and re-seeds it from the per-device listing (`resetTo` on a successful fetch, `clear()` on a failed
  one — capability `join-event`), then the new `eventId` is persisted, and only then does the arm `start()`
  the tier. The first cycle `start()` runs therefore already sees the seeded rows, so already-stored
  resources are `COMPLETED` before any upload job is created; the cycle itself seeds nothing and consults
  no join marker. Cancelling costs at most a re-upload of what was in flight — to the same
  device-partitioned, event-independent destination (`/files/devices/<deviceId>/<filename>`), so it is an
  idempotent overwrite — and whatever landed before the cancel is in the listing the load reads. A
  completion delivered after the load finds no row (or a seeded one) and changes nothing the load did not
  already account for. There SHALL be no disable→enable toggle and no cross-process race.
- **leave**: `stop()` (cancel the in-flight tasks and the scheduled task, leaving the session intact),
  then **clear the upload ledger**, then clear the stored `eventId` (the order is `LeaveEvent`'s —
  capability `leave-event`). The clear is the leave's, not `stop()`'s. The ledger is the current
  membership's share set, so a device that has left holds none; the next join re-seeds it from the
  per-device listing, so nothing already stored re-uploads unless that fetch fails. A completion delivered
  after the clear finds no row, and its outcome is acknowledged and discarded — its bytes are on the backend,
  where the next join's listing finds them. The cycle holds no leave-side action: after the leave it reads
  the membership as absent and uploads nothing.

#### Scenario: Re-provision is an in-process ordered sequence

- **WHEN** a new valid event link for a different event is scanned on iOS 18–26.0
- **THEN** the app stops the tier, clears the ledger and re-seeds it from the per-device listing, persists the new event, and only then starts the tier — so the first cycle finds already-stored resources `COMPLETED` before any upload job is created, with no OS toggle and no cross-process timing hazard

#### Scenario: A switch cancels in-flight transfers before the ledger is reset

- **WHEN** an event switch occurs while uploads are in flight on iOS 18–26.0
- **THEN** those transfers are cancelled and their staged temp files deleted before the ledger is cleared and re-seeded; what landed before the cancel is seeded `COMPLETED` from the listing, and the rest re-uploads to the same device-partitioned destination

#### Scenario: Enabling arms the heartbeat

- **WHEN** the app-driven producer's `start()` runs
- **THEN** a restart is signalled to the cycle, a cycle runs, and the first `BGProcessingTask` is submitted

#### Scenario: Stopping preserves the ledger

- **WHEN** the app-driven producer's `stop()` runs (access revoked or a download-only membership)
- **THEN** in-flight tasks and the scheduled `BGProcessingTask` are cancelled, while every ledger row is left intact

#### Scenario: Leave cancels transfers and clears the ledger

- **WHEN** the user leaves the event on iOS 18–26.0
- **THEN** in-flight tasks and the scheduled `BGProcessingTask` are cancelled, then the upload ledger is cleared, then the stored `eventId` is cleared — and joining any event afterwards re-seeds the ledger from the per-device listing, so nothing already in the device's byte partition re-uploads unless that fetch fails

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
ledger write, clear or reset, and no upload job. The exposure is
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
- **THEN** the cycle skips, the ledger is untouched, and the device is still joined on the next readable
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
