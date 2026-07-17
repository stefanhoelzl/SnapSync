# ios-photokit-upload — delta for delete-dead-weight

## MODIFIED Requirements

### Requirement: Extension owns the single ledger writer

**On iOS ≥26.1** (the two-process PhotoKit tier) the extension process SHALL be the single holder of the `LedgerWriter` over the App-Group ledger, and the host app SHALL NOT construct a `LedgerWriter`. This binds the ledger's single-record-writer invariant (see `sync-ledger`) to the extension process on this tier. On iOS 18–26.0 there is no extension process and the **app** holds the writer (see `ios-url-session-upload`); the invariant is preserved on both tiers, only its process binding differs.

#### Scenario: Only the extension writes on ≥26.1
- **WHEN** the app and extension are both assembled on iOS ≥26.1
- **THEN** the extension constructs the `LedgerWriter` and the app constructs none — it reads the ledger only through `LedgerBackend`'s read and reset-family operations

### Requirement: Completion and retry adjudication

The extension SHALL adjudicate the system's returned upload jobs each cycle, **before** discovering
new work (so completed/failed slots are freed first), and reduce each outcome into the engine. It
SHALL recover a returned `PHAssetResourceUploadJob`'s ledger key from the job's **destination URL**
(the last path segment) — the only field reliably present for every job state, since `resource` is
**nil for succeeded jobs** (the system releases it after upload). Version/attempt come from the
ledger (the `LedgerWriter`'s per-key `entry` read); the `resource`, when still present, is reused
only to re-create a
retry-spent job. **Every presented job SHALL be acknowledged** — including one whose key is
unrecoverable — or the system reports `appex failed to acknowledge jobs for processing state`
(error 50008). The two phases:

- **`fetchJobsWithAction(.retry)` (first failures):** map `job.error` → `UploadError`, report
  `UploadFailed` (engine records `FAILED`, answers `Retry` with a rebuilt edge URL — stable, no
  expiry, nothing to re-mint), call `retryWithDestination(:)`, then report `UploadStarted` (records
  `REQUESTED` at the incremented attempt).
- **`fetchJobsWithAction(.acknowledge)` (terminal):** `state == Succeeded` → `UploadCompleted`
  (records `COMPLETED`) then `acknowledge`; already-`COMPLETED` in the ledger → `acknowledge`
  (idempotent no-op); otherwise (a retry-spent `Failed`/`Cancelled` job) → `UploadFailed` (records
  `FAILED`) and, **if `resource` is still available**, create a fresh job with
  `creationRequestForJob(rebuiltURL, job.resource)` then `UploadStarted`. The job SHALL be
  acknowledged **regardless of the re-create outcome** (on the cap, acknowledge and let rediscovery
  retry the key — never leave a presented job un-acknowledged). Retry has no attempt budget (retry
  forever).

When the extension reconstructs the engine `Resource` for a returned job whose **ledger row is absent**
(pruned), it SHALL derive the resource `assetId` from the job key via the **shared**
`assetIdFromUploadKey` parser (the exact inverse of `uploadKey`; see `gallery-status`) — never a
placeholder such as an empty string — and SHALL record a terminal state only for a job whose key is
recoverable. It SHALL NOT write a `COMPLETED` (or other) row carrying a phantom `assetId=""`.

#### Scenario: Succeeded job records COMPLETED
- **WHEN** a job in the `.acknowledge` set has `state == Succeeded`
- **THEN** the extension reads its key from the job's destination URL, reports `UploadCompleted`
  (the ledger becomes `COMPLETED`), and acknowledges the job

#### Scenario: First failure retries with a rebuilt URL
- **WHEN** a job is returned in the `.retry` set
- **THEN** the extension reports `UploadFailed`, obtains a `Retry` with a locally rebuilt edge
  destination (byte-identical to the original — no expiry), calls `retryWithDestination(:)`, and
  reports `UploadStarted` so the ledger holds `REQUESTED` at the incremented attempt

#### Scenario: Retry-spent failure re-creates from the job's resource
- **WHEN** a `Failed` job appears in the `.acknowledge` set (its one system retry is spent) and its
  `resource` is still available
- **THEN** the extension reports `UploadFailed`, creates a fresh job using `job.resource`, reports
  `UploadStarted`, and acknowledges the original

#### Scenario: Every presented job is acknowledged
- **WHEN** a returned job's key cannot be recovered, or its re-create hits the cap, or its resource
  is unavailable
- **THEN** the job is still acknowledged (no `COMPLETED`/`UploadStarted` recorded), so the system
  never reports error 50008

#### Scenario: Already-completed re-handed job is a no-op
- **WHEN** a returned job maps to a key the ledger already holds as `COMPLETED`
- **THEN** the job is acknowledged and nothing is written or re-created

#### Scenario: A pruned-row completion derives assetId from the key
- **WHEN** a succeeded job is completed but its ledger row was already pruned (no entry)
- **THEN** the reconstructed resource carries the `assetId` parsed from the job key by
  `assetIdFromUploadKey` (not an empty string), and no phantom `assetId=""` row is recorded
