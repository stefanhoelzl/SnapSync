## MODIFIED Requirements

### Requirement: Engine-gated real upload-job creation

For each discovered `Resource` the extension SHALL drive the shared `SyncEngine` with
`ResourceChanged` and act on the decision. On a `Work` decision (`Upload`) it SHALL build the
destination request from the real `EdgeUploadRequestProvider` (a plain `PUT` to the locally-built,
**event-independent** edge URL defined by `edge-upload-provider`, no signing), create a
system upload job via `creationRequestForJob(destination:resource:)`, and **then** report
`UploadStarted(request)` to the engine so the ledger records `REQUESTED` (write-after-act — `REQUESTED`
is recorded only after the job exists, never before). The engine remains **event-blind** and keys by
the bare `filename`; ack-path recovery reads the destination URL's **last path segment**, which is the
unchanged `filename` (the byte URL's last segment; format per `edge-upload-provider`). On
`AlreadyUploaded` it SHALL
create no job and write nothing. Completion and failure outcomes are reduced into the ledger by the
drain (see "Completion and retry adjudication"), so a success is recorded `COMPLETED` and a failure returns its
row to `DISCOVERED`.

#### Scenario: New resource emits a real device-partitioned edge destination, then records REQUESTED
- **WHEN** the engine returns a `Work` decision for a discovered resource
- **THEN** a real edge `PUT` destination is built locally by `edge-upload-provider`,
  a system upload job is created with it, and only after the create succeeds does the extension report
  `UploadStarted`, which records `REQUESTED` for the key

#### Scenario: Already-recorded resource is skipped
- **WHEN** the engine returns `AlreadyUploaded` for a discovered resource (its key is `REQUESTED` or
  `COMPLETED`)
- **THEN** no system job is created and the ledger is not written

#### Scenario: Create failure leaves no REQUESTED
- **WHEN** `creationRequestForJob` fails (e.g. `limitExceeded`) before `UploadStarted` is reported
- **THEN** the ledger has no `REQUESTED` for that key, so a later re-derivation re-issues the create

### Requirement: Extension registration is a disable→enable toggle

**On iOS ≥26.1**, on a full photo-access grant the app SHALL register the background-upload extension with a
**disable→enable toggle** — `setUploadJobExtensionEnabled(false)` then `setUploadJobExtensionEnabled(true)` — rather than a bare enable. The system's `AssetResourceUploadJobConfiguration` is keyed by bundle id and **persists across app delete/reinstall and device reboot**; a stale record (e.g. left by a differently-signed build) makes a bare `enable(true)` fail with `PHPhotosError 3202` ("existing configuration record"), after which the system never launches the extension. The leading `enable(false)` deletes the stale record so `enable(true)` re-creates it cleanly for the currently-installed extension. On iOS 18–26.0 there is no such OS toggle; "enable" starts the app-driven pump and "disable" cancels it (see `ios-url-session-upload`).

The registration change SHALL be made through a **port** in `:domain` `ports/`, named for the need, whose
iOS adapter — the only implementation that calls `PHPhotoLibrary.setUploadJobExtensionEnabled` or
`isUploadJobExtensionEnabled` — lives in `:adapter:ios:app-only`, because only the app process ever
registers. The mechanism that performs the ritual SHALL hold no platform call of its own — its repair reaches
the ledger through the ledger's own port (see "Re-registering the extension demotes orphaned REQUESTED rows") — and SHALL therefore live in `:domain` `feature/upload` beside the
app-driven tier's mechanism, named for the need rather than for the platform. This is the ports law applied where it was not: the call sat in
`:app:ios`, which is wiring-only and gated at `CyclomaticComplexMethod` threshold 2, so it could report the
platform's raw facts but could hold no decision about them. Behind a port, the ritual, its repair,
and every arm of the outcome classification become executable on any host that can implement the port,
including JVM.

The registration record is OS state that this repo does not own, exactly as the upload-job queue is. Where
a target's host cannot hold such a record, the port's binding for that target answers in its place; see
"The upload-job subsystem binding is fixed by the compilation target".

#### Scenario: Stale registration is replaced, not rejected
- **WHEN** the app registers the extension on a grant on iOS ≥26.1 and a configuration record already exists
- **THEN** the existing record is deleted and a fresh one is inserted (no `3202` rejection), and the system can launch the extension

#### Scenario: The mechanism holds no platform call
- **WHEN** the mechanism that performs the disable→enable ritual is compiled
- **THEN** it names no platform API at all — the registration change and its read-back are reached through
  the registration port, and the repair through the ledger port — so it compiles for every
  target the platform-free core does

#### Scenario: The ritual is executable off a device
- **WHEN** the ritual runs against a port implementation that reports a pre-existing configuration record
- **THEN** the leading disable reports that a record existed and was removed, the enable reports success,
  and the sequence is asserted without a physical device

#### Scenario: The repair completes before the re-enable
- **WHEN** the ritual runs while the ledger holds orphaned `REQUESTED` rows
- **THEN** the rows are demoted to `DISCOVERED` **before** the enable is attempted, so the repair cannot demote
  rows belonging to the registration it is about to re-create

#### Scenario: Stopping is the disable alone
- **WHEN** the OS-driven mechanism's `stop()` runs — on a leave, or to relinquish it to the app-driven one
- **THEN** the registration is removed (or, under a partial grant, the attempt is refused) and no ledger
  row is touched; the next mechanism start repairs any row left `REQUESTED`

### Requirement: Completion and retry adjudication

The extension SHALL adjudicate the system's returned upload jobs each cycle, **before** discovering
new work (so completed/failed slots are freed first). It SHALL recover a returned
`PHAssetResourceUploadJob`'s ledger row by matching the job's **destination URL path** against the
`destinationPath` recorded for that row when the job was created (capability `sync-ledger`), read through the
`TransferRecord` the adapter is given. The destination is the only field reliably present for every job
state, since `resource` is **nil for succeeded jobs** (the system releases it after upload) — but under the v2
byte route its last path segment is the resource's **role**, not the ledger key, so the key SHALL NOT be read
from it.

For a job whose destination path matches no recorded row — including one created by a build that predates
the recorded path — the extension SHALL fall back to recovering the key from the destination URL's **last
path segment**, which is correct for the v1 destination shape and for nothing else.

A job whose row cannot be recovered by either route SHALL be **counted and reported at `Error` severity**,
naming how many such jobs a cycle saw. It SHALL NOT be silently drained: an unrecoverable job means an
upload whose outcome is being discarded, and a device in that state uploads bytes that are never recorded,
never listed in the manifest, and never visible to another member — with the row left `REQUESTED`, which no
routine path clears.

It SHALL likewise recover the job's **content type** from that same destination's `Content-Type` header
(matched case-insensitively, a blank value treated as absent), falling back to the `resource`'s uniform
type identifier and then to `application/octet-stream`. Deriving the content type from `resource` alone is
silently wrong for the same reason the key is not taken from it: a succeeded job has none, so a retried
upload rebuilt its request as `application/octet-stream` and every object that had ever failed once was
stored with that type. That the destination's headers survive the system's job store — not merely its URL —
is measured on device (SE2 / iOS 26.6), on both the `.retry` and `.acknowledge` sets; re-measure if the tier
moves to the iOS 27 `PHBackgroundResourceUploadJobExtension`.
The `resource`, when still present, is reused
only to re-create a
retry-spent job. **Every presented job SHALL be acknowledged** — including one whose row is
unrecoverable — or the system reports `appex failed to acknowledge jobs for processing state`
(error 50008). The two phases:

- **`fetchJobsWithAction(.retry)` (first failures):** map `job.error` → `UploadError`, report
  `UploadFailed` (engine records `DISCOVERED`, answers `Retry` with a rebuilt edge URL — stable, no
  expiry, nothing to re-mint), call `retryWithDestination(:)`, then report `UploadStarted` (records
  `REQUESTED`). The system job `retryWithDestination(:)` is applied to SHALL be
  found by the **same route** the job's key was recovered by — its destination path against the recorded
  `destinationPath`, then the v1 last-segment fallback — and SHALL NOT be found by comparing the destination's
  last path segment to the ledger key, which under the v2 route is the resource's role and matches no key.
  A retry whose system job is no longer in the `.retry` set SHALL be logged and SHALL NOT be silent.
- **`fetchJobsWithAction(.acknowledge)` (terminal):** the adapter SHALL record the outcome into the ledger
  itself, through the guarded `markTerminal` of its `TransferRecord` (`sync-ledger`), and acknowledge the job
  **in place** — `state == Succeeded` → record `COMPLETED`, then acknowledge; a key already in a terminal
  state → acknowledge (the guard applies to nothing, an idempotent no-op); otherwise (a retry-spent
  `Failed`/`Cancelled` job) → record `DISCOVERED` (the failed outcome), then acknowledge. The job SHALL be acknowledged **regardless** of
  whether its guarded write applied and regardless of any re-create outcome (never leave a presented job
  un-acknowledged). Retry has no attempt budget (retry forever).

A succeeded job SHALL become `COMPLETED` directly. Nothing a completion used to trigger is still owed: the
device manifest declared the resource when it was discovered (capability `device-manifest`), and the
event-album placement happened when its upload was first enqueued (capability `event-album`). No later pass
reads or re-settles the row.

Only **retry-spent failures whose `resource` is still available** SHALL be returned from the drain, so the
cycle can re-create them in the same cycle from a live resource. No succeeded job and no terminal fact SHALL
cross the port.

When the extension reconstructs a resource for a returned job whose **ledger row is absent**
(pruned), it SHALL derive the resource `assetId` from the recovered key via the **shared**
`assetIdFromUploadKey` parser (the exact inverse of `uploadKey`; see `gallery-status`) — never a
placeholder such as an empty string — and SHALL record a terminal state only for a job whose row is
recoverable. It SHALL NOT write a row carrying a phantom `assetId=""`.

#### Scenario: A returned job is matched by its destination path
- **WHEN** a job in the `.acknowledge` set carries a destination whose path equals the `destinationPath`
  recorded for a ledger row
- **THEN** that row is the job's row, whatever the destination's last path segment happens to be

#### Scenario: The role token is never mistaken for the key
- **WHEN** a job's destination is the v2 byte route, whose last path segment is the resource's role
- **THEN** the extension does not treat that segment as a ledger key, and no row keyed `primary` or `live`
  is ever written

#### Scenario: A job created by the previous build still resolves
- **WHEN** a job's destination path matches no recorded row and its shape is the v1 byte route
- **THEN** the key is recovered from the destination's last path segment and the job is adjudicated normally

#### Scenario: An unrecoverable job is reported, not drained silently
- **WHEN** a cycle presents one or more jobs whose rows cannot be recovered by either route
- **THEN** the count is reported at `Error` severity, and every such job is still acknowledged

#### Scenario: Succeeded job records COMPLETED
- **WHEN** a job in the `.acknowledge` set has `state == Succeeded`
- **THEN** the extension resolves its row from the job's destination path, records that row `COMPLETED`, and
  acknowledges the job — and no later pass of the cycle reads or writes that row again

#### Scenario: A retried upload keeps its original content type
- **WHEN** a job is returned for retry or re-creation, so its `Resource` is rebuilt from the key alone
  with no metadata, and `resource` may be nil
- **THEN** the rebuilt request's `Content-Type` is the one read back from the job's stored destination
  header — not `application/octet-stream` — so the object is stored with the type it was uploaded under

#### Scenario: First failure retries with a rebuilt URL
- **WHEN** a job is returned in the `.retry` set
- **THEN** the extension reports `UploadFailed`, obtains a `Retry` with a locally rebuilt edge
  destination (byte-identical to the original — no expiry), calls `retryWithDestination(:)`, and
  reports `UploadStarted` so the ledger holds `REQUESTED`

#### Scenario: A v2-route retry reaches its system job
- **WHEN** a job is returned in the `.retry` set whose destination is the v2 byte route, so its last path
  segment is the resource's role
- **THEN** the system job whose destination path resolves to that key is the one `retryWithDestination(:)`
  is applied to, and no "no live retry job" line is logged for it

#### Scenario: Retry-spent failure re-creates from the job's resource
- **WHEN** a `Failed` job appears in the `.acknowledge` set (its one system retry is spent) and its
  `resource` is still available
- **THEN** the extension records that row `DISCOVERED`, acknowledges the job, and returns it from the drain so
  the cycle creates a fresh job from the live resource

#### Scenario: A failure handed back for a completed key re-uploads nothing
- **WHEN** a retry-spent failure is returned from the drain for a key whose row is already `COMPLETED`
- **THEN** the cycle skips it as settled, writes nothing, and creates no job

#### Scenario: Every presented job is acknowledged
- **WHEN** a returned job's row cannot be recovered, or its guarded write applies to nothing, or its
  re-create hits the cap, or its resource is unavailable
- **THEN** the job is still acknowledged, so the system never reports error 50008

#### Scenario: Already-terminal re-handed job is a no-op
- **WHEN** a returned job maps to a row that is no longer `REQUESTED`
- **THEN** the guarded write applies to nothing, the job is acknowledged, and nothing is written or
  re-created

#### Scenario: A pruned-row completion derives assetId from the key
- **WHEN** a succeeded job is recorded but its ledger row was already pruned (no entry)
- **THEN** the guarded write applies to nothing, no phantom `assetId=""` row is created, and any resource
  reconstructed for a re-create carries the `assetId` parsed from the recovered key by `assetIdFromUploadKey`

### Requirement: Discovery prunes ledger rows for deleted assets

The extension SHALL record that an asset has left the library by **deleting** its ledger rows, and only on
the evidence capability `sync-ledger` defines ("Deletion is a presence diff over an authoritative walk"): an
authoritative walk that did not return the asset, for rows inside the membership's capture window that no
live job owns; or, for a single row that still needs a job, its key resolving to nothing. The ledger writes
preserve the single-writer invariant. No remote object is deleted: nothing on the device deletes an
uploaded object, and reclamation belongs entirely to the nightly sweep (capability `scheduled-cleanup`).
The one-way model is unchanged.

Deleting keeps the ledger honest about what still exists on device and, critically, removes a row left
non-`COMPLETED` by an asset deleted mid-upload. That row would otherwise keep `pending > 0` forever and hold
the extension in the perpetual `processing` re-invocation loop (see "Cap-aware creation and tri-state
processing result"). Because the device manifest is projected from those same rows, one deletion also makes
the next projected `device.json` stop listing the departed asset: there is no second structure to keep in
step.

The walk is narrowed by the membership's own selection policy, so "not returned" means gone from the
library **only inside the policy's capture window**. That is why the deletion is judged per row by the
policy's row admission and never by the admitted candidate set. The retired reconcile backstop was supplied
the policy-**admitted** set, so raising a capture cutoff discarded the `COMPLETED` rows of photos that were
still present. Judged by presence and row admission, a raised cutoff moves those rows out of the window
instead, and they survive. A membership whose direction excludes upload never reaches the walk at all.

Deletion is now exhaustive for a full grant: a deletion is observed by the first authoritative walk after
it, whenever that is, with no token to expire. A re-added asset (for example, recovered from "Recently
Deleted") SHALL be discovered as new work and re-uploaded under its same keys. Its rows are gone, so nothing
suppresses the upload. The backend re-stores the role idempotently and wakes nobody (capability
`api-endpoints`). No `DELETED` state is introduced and the upload decision is unchanged.

#### Scenario: A departed asset's rows are deleted by the next walk
- **WHEN** asset `L` has in-window `COMPLETED` rows and an authoritative walk does not return it
- **THEN** the extension deletes those rows before recording the walk's discoveries, so `L` contributes to
  neither `pending` nor `completed` and the next projected `device.json` omits it

#### Scenario: Mid-upload deletion lets the extension rest
- **WHEN** an asset deleted before its upload completed leaves a `DISCOVERED` row
- **THEN** its key resolves to nothing at enqueue and that row is deleted, the ledger reaches no pending
  rows, and `process()` can return `completed` instead of looping on `processing`

#### Scenario: A walk deletes nothing outside its window
- **WHEN** an authoritative walk completes and the ledger holds rows, outside the membership's capture
  window, for an asset the walk did not return
- **THEN** those rows are kept: the walk is policy-narrowed, so an asset's absence from it is not evidence
  that the asset left the library

#### Scenario: A narrowed scope costs no ledger rows
- **WHEN** the membership's capture cutoff is raised past an already-uploaded asset still in the library,
  and a full enumeration runs
- **THEN** that asset's `COMPLETED` rows survive, so lowering the cutoff again re-lists it with no byte
  re-uploaded

#### Scenario: Re-added asset re-uploads
- **WHEN** an asset whose rows a walk deleted reappears in the library (for example, recovered from
  "Recently Deleted")
- **THEN** the next walk records its resources `DISCOVERED`, their upload is re-created under the same
  keys, and the next projection lists it again

### Requirement: Re-registering the extension demotes orphaned REQUESTED rows

The app SHALL recover the in-flight jobs a disable wipes. Disabling the upload extension
(`setUploadJobExtensionEnabled(false)`) deletes the system's `AssetResourceUploadJobConfiguration` and
therefore **wipes every in-flight OS upload job**, and no API surfaces a vanished job. Without a recovery the
rows stay `REQUESTED` forever: the engine treats `REQUESTED` as in-flight and never re-issues it, and a
same-event cycle never reconciles — so the photos that were mid-upload are permanently abandoned.

The recovery SHALL run in this mechanism's **`start()`** — the disable→enable re-register — **between** the
disable and the enable, and SHALL be the ledger's reset-family `demoteRequested()` (`sync-ledger`): every
`REQUESTED` row becomes `DISCOVERED`. Every one of them is unsettleable at that moment: the disable has just wiped
this tier's jobs, and wherever the app-driven mechanism also exists, starting this mechanism is preceded by the
app-driven mechanism's `stop()` (`upload-lifecycle`, "The upload mechanism is resolved, never selected"), so
no app-driven transfer is carrying a row either.

A `DISCOVERED` row needs a job, so the ledger's work read returns it on the next cycle without any walk
re-deriving it. The former recovery *deleted* the rows, which only a walk that re-read the asset's
resources could re-surface; a demoted row needs no such walk.

This mechanism's **`stop()`** SHALL be the disable alone and SHALL repair nothing — on a leave and on a
relinquish to the app-driven mechanism alike. On a leave nothing uploads until a mechanism starts again, and
that start repairs; on a relinquish, the app-driven mechanism's own start repairs (`ios-url-session-upload`,
"Stranded reconciliation: scoped each cycle, complete at a start"). There SHALL therefore be no narrower
teardown verb for a hand-off.

The demote SHALL be **awaited off the main thread and completed before the enable**. The write SHALL run on
`Dispatchers.Default` (Kotlin/Native has no `Dispatchers.IO`), never on the `Dispatchers.Main` scope — it is a
synchronous SQLite write that on the main thread is a hang risk under cross-process WAL contention — and SHALL
use a small bounded retry around the write. `setUploadJobExtensionEnabled(true)` SHALL NOT be called until the
demote has completed, so a `REQUESTED` row the re-enabled extension records can never be demoted by a
still-running repair. The demote SHALL NOT be fire-and-forget. The bounded-retry, off-main helper is pure logic
and SHALL live in a tested `:domain` helper (`feature/upload`), not in the untested app shell.

The app SHALL use the `LedgerStore` directly (constructing no `LedgerWriter`): on this tier the extension is
the one recording process, and `demoteRequested` is a reset-family operation that a non-writer may perform.

#### Scenario: A re-register self-heals instead of orphaning

- **WHEN** photos are mid-upload (`REQUESTED` rows, OS jobs registered)
  and the app re-registers the extension (disable→enable)
- **THEN** the disable wipes the OS jobs and `demoteRequested()` marks the rows `DISCOVERED`, so the next
  cycle's work read re-creates the not-yet-stored jobs (bytes resume landing), with no permanently-stuck
  `REQUESTED` and no re-read of the assets' resources

#### Scenario: The re-enable does not race the repair

- **WHEN** the app re-registers the extension (disable→enable)
- **THEN** `demoteRequested()` runs off-main and completes **before** `setUploadJobExtensionEnabled(true)`
  is called, so no `REQUESTED` row recorded by the re-enabled extension is demoted by the repair

#### Scenario: The repair runs off the main thread

- **WHEN** a re-register triggers `demoteRequested()`
- **THEN** the SQLite write executes on `Dispatchers.Default` (not the `Dispatchers.Main` scope) with
  a bounded retry, and is awaited rather than launched fire-and-forget

#### Scenario: A stop repairs nothing

- **WHEN** the extension is disabled by this mechanism's `stop()` — a leave, or a relinquish to the app-driven
  mechanism — while `REQUESTED` rows exist
- **THEN** no ledger row changes, and the rows are demoted by the next mechanism start

#### Scenario: Completed rows survive the repair

- **WHEN** a re-register triggers `demoteRequested()` and the ledger holds `COMPLETED` rows for
  already-stored files
- **THEN** those `COMPLETED` rows are unchanged, so a subsequent reconcile/discovery does not re-upload
  already-stored bytes
