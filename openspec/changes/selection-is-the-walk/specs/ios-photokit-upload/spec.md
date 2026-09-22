## MODIFIED Requirements

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

A job whose destination has a recognised byte-route shape but whose **row is gone** SHALL be treated as
**pruned**, not unrecoverable. The walk deletes a departed or de-selected asset's rows whatever their state
(capability `sync-ledger`, "Deletion is a presence diff over an authoritative walk"), so a late job for one
is expected. A pruned job SHALL be acknowledged in place in whichever set presented it (`.retry` or
`.acknowledge`), SHALL write nothing, SHALL NOT be emitted to the cycle, and SHALL be logged at `Info`
(capability `upload-lifecycle`, "A presented job whose row is gone is answered and nothing more"). The v1
last-segment fallback SHALL recover a key only when a row exists for it. A job it recovers no row for is
pruned too. Reporting a pruned job at `Error` would raise a crash-reporting event for every photo deleted
or de-selected mid-upload.

A job whose destination cannot be mapped to any known shape SHALL be **counted and reported at `Error`
severity**, naming how many such jobs a cycle saw. It SHALL NOT be silently drained: an unmappable job means
an upload whose outcome is being discarded for a reason this build does not understand.

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

Only **retry-spent failures whose `resource` is still available and whose row exists** SHALL be returned
from the drain, so the cycle can re-create them in the same cycle from a live resource. No succeeded job, no
terminal fact and no pruned job SHALL cross the port.

When the extension reconstructs a resource for a returned job, it SHALL derive the resource `assetId` from
the recovered key via the **shared** `assetIdFromUploadKey` parser (the exact inverse of `uploadKey`; see
`gallery-status`) — never a placeholder such as an empty string. It SHALL record a terminal state only for a
job whose row exists, and SHALL NOT write any row for a pruned job, phantom or otherwise.

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

#### Scenario: An unmappable job is reported, not drained silently
- **WHEN** a cycle presents one or more jobs whose destinations match no known byte-route shape
- **THEN** the count is reported at `Error` severity, and every such job is still acknowledged

#### Scenario: A job for a pruned row is acknowledged quietly
- **WHEN** a job in either set carries a v2 destination whose row the walk deleted
- **THEN** it is acknowledged in place, nothing is written, it is not returned to the cycle, it is logged at
  `Info`, and no `Error` is reported

#### Scenario: A first failure for a pruned row is not retried
- **WHEN** a job in the `.retry` set belongs to a row the walk deleted
- **THEN** it is acknowledged, not handed to the cycle, and `retryWithDestination(:)` is not called

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

#### Scenario: A pruned-row completion writes nothing
- **WHEN** a succeeded or retry-spent job is presented but its ledger row was already pruned (no entry)
- **THEN** no row is written, phantom or otherwise, the job is acknowledged, and nothing is returned for
  re-creation

#### Scenario: A reconstructed resource derives assetId from the key
- **WHEN** a returned job's resource is reconstructed for a re-create
- **THEN** it carries the `assetId` parsed from the recovered key by `assetIdFromUploadKey`

### Requirement: Discovery prunes ledger rows for deleted assets

The extension SHALL record that an asset has left the library by **deleting** its ledger rows, and only on
the evidence capability `sync-ledger` defines ("Deletion is a presence diff over an authoritative walk"): an
authoritative walk that did not return the asset, for rows inside the membership's capture window, whatever
their upload state; or, for a single row that still needs a job, its key resolving to nothing. A row whose
job is still in flight is deleted too. The job's later outcome finds no row and is answered as pruned (see
"Completion and retry adjudication"). Each deletion is
one ledger transaction with its guard in the statement (capability `sync-ledger`), so it stays safe while the
app's cycle holds a `LedgerWriter` over the same ledger at the same time. No remote object is deleted: nothing on the device deletes an
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
instead, and they survive. A membership whose direction excludes upload never reaches the walk at all: its selection policy admits
nothing, and the cycle declines on that before walking.

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

#### Scenario: A deletion during an in-flight upload retracts the asset at once
- **WHEN** an asset whose row is `REQUESTED` is deleted from the library and an authoritative walk runs
- **THEN** the row is deleted and the next projected `device.json` omits the asset, and the job's later
  outcome writes nothing

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

