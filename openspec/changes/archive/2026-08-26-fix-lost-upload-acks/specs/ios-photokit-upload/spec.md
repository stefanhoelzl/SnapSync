## MODIFIED Requirements

### Requirement: Completion and retry adjudication

The extension SHALL adjudicate the system's returned upload jobs each cycle, **before** discovering
new work (so completed/failed slots are freed first). It
SHALL recover a returned `PHAssetResourceUploadJob`'s ledger key from the job's **destination URL**
(the last path segment) — the only field reliably present for every job state, since `resource` is
**nil for succeeded jobs** (the system releases it after upload). It SHALL likewise recover the job's
**content type** from that same destination's `Content-Type` header (matched case-insensitively, a blank
value treated as absent), falling back to the `resource`'s uniform type identifier and then to
`application/octet-stream`. Deriving the content type from `resource` alone is silently wrong for the
same reason the key is not taken from it: a succeeded job has none, so a retried upload rebuilt its
request as `application/octet-stream` and every object that had ever failed once was stored with that
type. That the destination's headers survive the system's job store — not merely its URL — is measured on
device (SE2 / iOS 26.6), on both the `.retry` and `.acknowledge` sets; re-measure if the tier moves to
the iOS 27 `PHBackgroundResourceUploadJobExtension`.
Version/attempt come from the
ledger; the `resource`, when still present, is reused
only to re-create a
retry-spent job. **Every presented job SHALL be acknowledged** — including one whose key is
unrecoverable — or the system reports `appex failed to acknowledge jobs for processing state`
(error 50008). The two phases:

- **`fetchJobsWithAction(.retry)` (first failures):** map `job.error` → `UploadError`, report
  `UploadFailed` (engine records `FAILED`, answers `Retry` with a rebuilt edge URL — stable, no
  expiry, nothing to re-mint), call `retryWithDestination(:)`, then report `UploadStarted` (records
  `REQUESTED` at the incremented attempt).
- **`fetchJobsWithAction(.acknowledge)` (terminal):** the adapter SHALL record the outcome into the ledger
  itself, through the guarded `markTerminal` (`sync-ledger`), and acknowledge the job **in place** —
  `state == Succeeded` → record `UPLOADED`, then acknowledge; a key already in a terminal state → acknowledge
  (the guard applies to nothing, an idempotent no-op); otherwise (a retry-spent `Failed`/`Cancelled` job) →
  record `FAILED`, then acknowledge. The job SHALL be acknowledged **regardless** of whether its guarded
  write applied and regardless of any re-create outcome (never leave a presented job un-acknowledged). Retry
  has no attempt budget (retry forever).

A succeeded job SHALL become `UPLOADED`, not `COMPLETED`: the cycle's shared promotion pass performs the
work a completion triggers — event-album placement and the completion notify — and then promotes. Recording
`COMPLETED` here would make that pass see a settled row and skip both.

Only **retry-spent failures whose `resource` is still available** SHALL be returned from the drain, so the
cycle can re-create them in the same cycle from a live resource. No succeeded job and no terminal fact SHALL
cross the port.

When the extension reconstructs a resource for a returned job whose **ledger row is absent**
(pruned), it SHALL derive the resource `assetId` from the job key via the **shared**
`assetIdFromUploadKey` parser (the exact inverse of `uploadKey`; see `gallery-status`) — never a
placeholder such as an empty string — and SHALL record a terminal state only for a job whose key is
recoverable. It SHALL NOT write a row carrying a phantom `assetId=""`.

#### Scenario: Succeeded job records UPLOADED
- **WHEN** a job in the `.acknowledge` set has `state == Succeeded`
- **THEN** the extension reads its key from the job's destination URL, records that row `UPLOADED`, and
  acknowledges the job — and the cycle's promotion pass later places it in the album, notifies, and promotes
  it to `COMPLETED`

#### Scenario: A retried upload keeps its original content type
- **WHEN** a job is returned for retry or re-creation, so its `Resource` is rebuilt from the key alone
  with no metadata, and `resource` may be nil
- **THEN** the rebuilt request's `Content-Type` is the one read back from the job's stored destination
  header — not `application/octet-stream` — so the object is stored with the type it was uploaded under

#### Scenario: First failure retries with a rebuilt URL
- **WHEN** a job is returned in the `.retry` set
- **THEN** the extension reports `UploadFailed`, obtains a `Retry` with a locally rebuilt edge
  destination (byte-identical to the original — no expiry), calls `retryWithDestination(:)`, and
  reports `UploadStarted` so the ledger holds `REQUESTED` at the incremented attempt

#### Scenario: Retry-spent failure re-creates from the job's resource
- **WHEN** a `Failed` job appears in the `.acknowledge` set (its one system retry is spent) and its
  `resource` is still available
- **THEN** the extension records that row `FAILED`, acknowledges the job, and returns it from the drain so
  the cycle creates a fresh job from the live resource

#### Scenario: Every presented job is acknowledged
- **WHEN** a returned job's key cannot be recovered, or its guarded write applies to nothing, or its
  re-create hits the cap, or its resource is unavailable
- **THEN** the job is still acknowledged, so the system never reports error 50008

#### Scenario: Already-terminal re-handed job is a no-op
- **WHEN** a returned job maps to a key whose row is no longer `REQUESTED`
- **THEN** the guarded write applies to nothing, the job is acknowledged, and nothing is written or
  re-created

#### Scenario: A pruned-row completion derives assetId from the key
- **WHEN** a succeeded job is recorded but its ledger row was already pruned (no entry)
- **THEN** the guarded write applies to nothing, no phantom `assetId=""` row is created, and any resource
  reconstructed for a re-create carries the `assetId` parsed from the job key by `assetIdFromUploadKey`
