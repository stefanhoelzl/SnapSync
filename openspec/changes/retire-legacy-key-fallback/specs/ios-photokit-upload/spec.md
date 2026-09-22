## MODIFIED Requirements

### Requirement: Engine-gated real upload-job creation

For each discovered `Resource` the extension SHALL drive the shared `SyncEngine` with
`ResourceChanged` and act on the decision. On a `Work` decision (`Upload`) it SHALL build the
destination request from the real `EdgeUploadRequestProvider` (a plain `PUT` to the locally-built,
**event-independent** edge URL defined by `edge-upload-provider`, no signing), create a
system upload job via `creationRequestForJob(destination:resource:)`, and **then** report
`UploadStarted(request)` to the engine so the ledger records `REQUESTED` (write-after-act — `REQUESTED`
is recorded only after the job exists, never before). The engine remains **event-blind** and keys by
the bare `filename`; ack-path recovery matches the job's destination path against the `destinationPath`
recorded by that `REQUESTED` write (see "Completion and retry adjudication"), never the destination's last path
segment, which under the v2 byte route is the resource's role (format per `edge-upload-provider`). On
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

### Requirement: Completion and retry adjudication

The extension SHALL adjudicate the system's returned upload jobs each cycle, **before** discovering
new work (so completed/failed slots are freed first). It SHALL recover a returned
`PHAssetResourceUploadJob`'s ledger row by matching the job's **destination URL path** against the
`destinationPath` recorded for that row when the job was created (capability `sync-ledger`), read through the
`TransferRecord` the adapter is given. The destination is the only field reliably present for every job
state, since `resource` is **nil for succeeded jobs** (the system releases it after upload) — but under the v2
byte route its last path segment is the resource's **role**, not the ledger key, so the key SHALL NOT be read
from it.

The recorded destination path SHALL be the **only** route from a job to its row. There SHALL be no fallback
that reads a key out of the destination: the v1 last-segment recovery, which served only jobs created before
the v2 byte route shipped, is retired (decision record `changes/retire-legacy-key-fallback`). A row written
before `destinationPath` existed therefore cannot be resolved from a job.

A job whose destination has a recognised byte-route shape but whose **row is gone** SHALL be treated as
**pruned**, not unrecoverable. The walk deletes a departed or de-selected asset's rows whatever their state
(capability `sync-ledger`, "Deletion is a presence diff over an authoritative walk"), so a late job for one
is expected. A pruned job SHALL be acknowledged in place in whichever set presented it (`.retry` or
`.acknowledge`), SHALL write nothing, SHALL NOT be emitted to the cycle, and SHALL be logged at `Info`
(capability `upload-lifecycle`, "A presented job whose row is gone is answered and nothing more"). Reporting a
pruned job at `Error` would raise a crash-reporting event for every photo deleted or de-selected mid-upload.
The recognised byte-route shape SHALL be the v2 shape `/files/devices/<deviceId>/<assetId>/<role>` only.

A job whose destination cannot be mapped to any known shape SHALL be **counted and reported at `Error`
severity**, naming how many such jobs a cycle saw. It SHALL NOT be silently drained: an unmappable job means
an upload whose outcome is being discarded for a reason this build does not understand. A job with a **v1**
destination (`/files/devices/<deviceId>/<key>`) SHALL be treated as unmappable, not pruned: its row may still
exist and be `REQUESTED`, which a quiet prune would hide.

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
  `destinationPath` — and SHALL NOT be found by comparing the destination's
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

#### Scenario: A v1-shaped job is unmappable, not resolved
- **WHEN** a job in either set carries a v1 destination `/files/devices/<deviceId>/<key>`, whether or not a row
  keyed by its last path segment exists
- **THEN** no key is read from its path, nothing is written, it is not returned to the cycle, it is
  acknowledged, and it is counted in the cycle's `Error`-severity unmappable report

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
