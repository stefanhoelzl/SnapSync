## MODIFIED Requirements

### Requirement: In-extension discovery via persistent change token

On each `process()` invocation, the extension SHALL discover work itself (the system does not
enumerate). On first run (no token) it SHALL enumerate the whole library via `PHAsset.fetchAssets`
and capture `currentChangeToken` as baseline; in steady state it SHALL call
`fetchPersistentChanges(since:)` and derive the changed asset set. On `persistentChangeTokenExpired`
it SHALL re-enumerate the whole library, relying on the ledger to skip already-recorded keys. The
change token SHALL be **persisted across extension process death** in a shared App-Group store (an
archived `PHPersistentChangeToken`; see "Persisted change-token cursor"), so a short-lived wake
resumes incrementally instead of re-enumerating the whole library. The token SHALL be advanced
(persisted to `currentChangeToken`) **only at the end of a fully-drained cycle** — a cycle in which
every discovered resource was turned into a job with no `limitExceeded`. On a cap-truncated cycle the
token SHALL NOT advance, so the next wake re-derives the same change set (the engine's
`REQUESTED`-skip prevents duplicate jobs for work already created).

#### Scenario: First run enumerates the whole library
- **WHEN** `process()` runs with no persisted change token
- **THEN** the extension enumerates the full library and records the current change token as the
  baseline cursor in the App-Group store

#### Scenario: Cursor survives a process restart
- **WHEN** the extension process is torn down after a fully-drained cycle and later re-invoked
- **THEN** it loads the persisted token and calls `fetchPersistentChanges(since:)` from it, rather
  than re-enumerating the whole library

#### Scenario: Token does not advance on a cap-truncated cycle
- **WHEN** a cycle stops early because `creationRequestForJob` raised `PHPhotosErrorLimitExceeded`
- **THEN** the persisted token is left unchanged, so the next wake re-derives the same change set and
  the engine skips the resources whose jobs already exist (`REQUESTED`)

#### Scenario: Token expiry re-enumerates harmlessly
- **WHEN** `fetchPersistentChanges(since:)` reports `persistentChangeTokenExpired`
- **THEN** the extension re-enumerates the whole library and the ledger answers `AlreadyUploaded` for
  keys already recorded, so no duplicate jobs are created

### Requirement: Engine-gated real upload-job creation

For each discovered `Resource` the extension SHALL drive the shared `SyncEngine` with
`ResourceChanged` and act on the decision. On a `Work` decision (`Upload`/`ReUpload`) it SHALL build
the destination request from the real `S3UploadRequestProvider` (a presigned S3 `PUT` minted from the
assembled `S3Config`), create a system upload job via `creationRequestForJob(destination:resource:)`,
and **then** report `UploadStarted(job)` to the engine so the ledger records `REQUESTED`
(write-after-act — `REQUESTED` is recorded only after the job exists, never before). On
`AlreadyUploaded` it SHALL create no job and write nothing. Completion and failure outcomes are
reduced into the ledger by the drain (see "Completion and retry adjudication"), so `COMPLETED` and
`FAILED` are now recorded — the prior prohibition on recording `COMPLETED` no longer applies.

#### Scenario: New resource emits a real presigned destination, then records REQUESTED
- **WHEN** the engine returns a `Work` decision for a discovered resource
- **THEN** a real presigned S3 `PUT` destination is minted, a system upload job is created with it,
  and only after the create succeeds does the extension report `UploadStarted`, which records
  `REQUESTED` for the key

#### Scenario: Already-recorded resource is skipped
- **WHEN** the engine returns `AlreadyUploaded` for a discovered resource (its key is `REQUESTED` or
  `COMPLETED` at the same version)
- **THEN** no system job is created and the ledger is not written

#### Scenario: Create failure leaves no REQUESTED
- **WHEN** `creationRequestForJob` fails (e.g. `limitExceeded`) before `UploadStarted` is reported
- **THEN** the ledger has no `REQUESTED` for that key, so a later re-derivation re-issues the create

## REMOVED Requirements

### Requirement: Drain-all job disposition

**Reason:** Replaced by "Completion and retry adjudication". The extension no longer acknowledges
every job blindly; it inspects each job's outcome and reduces it into the ledger (record `COMPLETED`
on success; record `FAILED` and retry on failure). The idempotent no-op for a re-handed
already-`COMPLETED` job is preserved within the new requirement.

## ADDED Requirements

### Requirement: Completion and retry adjudication

The extension SHALL adjudicate the system's returned upload jobs each cycle, **before** discovering
new work (so completed/failed slots are freed first), and reduce each outcome into the engine. It
SHALL recover a returned `PHAssetResourceUploadJob`'s ledger key by recomputing
`uploadKey(assetId = job.assetLocalIdentifier with '/'→'_', resourceType = job.resource.type,
originalFilename = job.resource.originalFilename)` — the same key function discovery uses — and obtain
that key's `version`/`attempt` from the ledger (`LedgerReader`); it SHALL NOT parse the destination
URL and SHALL NOT keep a separate retention store. The two phases:

- **`fetchJobsWithAction(.retry)` (first failures):** for each job, map `job.error` to an
  `UploadError`, report `UploadFailed` (engine records `FAILED`, answers `Retry` with a freshly
  presigned URL), call `retryWithDestination(:)` with that URL, then report `UploadStarted` (records
  `REQUESTED` at the incremented attempt).
- **`fetchJobsWithAction(.acknowledge)` (terminal):** if `job.state == Succeeded`, report
  `UploadCompleted` (records `COMPLETED`) then `acknowledge`; if the key is already `COMPLETED` in the
  ledger, `acknowledge` as an idempotent no-op; otherwise (a `Failed`/`Cancelled` job whose single
  system retry is spent) report `UploadFailed` (records `FAILED`), create a fresh job with
  `creationRequestForJob(freshURL, job.resource)`, and **acknowledge only if the re-create succeeded**
  — on `limitExceeded` leave the job un-acknowledged so the system re-presents it next cycle — then
  report `UploadStarted`. Retry has no attempt budget (retry forever).

#### Scenario: Succeeded job records COMPLETED
- **WHEN** a job in the `.acknowledge` set has `state == Succeeded`
- **THEN** the extension recomputes its key, reports `UploadCompleted` (the ledger becomes
  `COMPLETED`), and acknowledges the job

#### Scenario: First failure retries with a fresh URL
- **WHEN** a job is returned in the `.retry` set
- **THEN** the extension reports `UploadFailed`, obtains a `Retry` with a freshly presigned
  destination, calls `retryWithDestination(:)`, and reports `UploadStarted` so the ledger holds
  `REQUESTED` at the incremented attempt

#### Scenario: Retry-spent failure re-creates from the job's resource
- **WHEN** a `Failed` job appears in the `.acknowledge` set (its one system retry is spent)
- **THEN** the extension reports `UploadFailed`, creates a fresh job using `job.resource` directly,
  acknowledges the original only after the re-create succeeds, and reports `UploadStarted`

#### Scenario: Re-create hitting the cap is not acknowledged
- **WHEN** the re-create of a retry-spent failure raises `limitExceeded`
- **THEN** the original job is left un-acknowledged so the system re-presents it on a later cycle,
  and the token is not advanced

#### Scenario: Already-completed re-handed job is a no-op
- **WHEN** a returned job maps to a key the ledger already holds as `COMPLETED`
- **THEN** the job is acknowledged and nothing is written or re-created

### Requirement: Cap-aware creation and tri-state processing result

When `creationRequestForJob` raises `PHPhotosErrorLimitExceeded`, the extension SHALL stop creating
jobs for the remainder of the cycle, leave the change token un-advanced, and surface a **processing**
result so the system re-invokes it promptly; on the next wake, re-derivation plus the engine's
`REQUESTED`-skip resumes exactly the un-created remainder with no duplicate jobs and no persisted
residue list. The Kotlin `process()` SHALL return a tri-state result (`completed` / `processing` /
`failure`) that the Swift principal class maps to `PHBackgroundResourceUploadProcessingResult`
(`.completed` / `.processing` / `.failure`); if the iOS 26.1 SDK lacks a `.processing` case the Swift
shell SHALL fall back to `.completed` (correctness is unaffected — the un-advanced token drains the
remainder on the next system-scheduled wake; only promptness is lost).

#### Scenario: Cap during discovery yields a processing result
- **WHEN** job creation hits `limitExceeded` partway through a cycle
- **THEN** the extension stops creating jobs, does not advance the token, and `process()` returns a
  processing result (mapped to `.processing`, or `.completed` if unavailable)

#### Scenario: Re-entry resumes the remainder without duplicates
- **WHEN** a cap-truncated cycle is followed by another `process()` invocation
- **THEN** the same change set is re-derived, the already-created jobs are skipped (`REQUESTED`), and
  only the previously un-created resources get new jobs

### Requirement: Persisted change-token cursor

The discovery cursor SHALL be persisted in the shared App-Group store written by the extension. The
extension SHALL archive the `PHPersistentChangeToken` (via its `NSSecureCoding` support) to `Data`
and store it in App-Group `NSUserDefaults` (suite `group.app.snapsync`), reading it back at cycle
start. The cursor's load/advance orchestration SHALL be platform-free (a `commonMain` port over
opaque token bytes) so it is exercised on the simulator with a fake; the `NSUserDefaults` archiving
is untested iosMain wiring. Persistence is an efficiency optimization only: a cold start with no
stored token re-enumerates the whole library, which the ledger makes harmless.

#### Scenario: Token round-trips through the App-Group store
- **WHEN** the extension advances the cursor at the end of a fully-drained cycle
- **THEN** the archived token bytes are written to App-Group `NSUserDefaults` and a subsequent
  process reads them back and resumes `fetchPersistentChanges(since:)` from that token

#### Scenario: Missing token falls back to full enumeration
- **WHEN** `process()` runs with no token in the App-Group store
- **THEN** the extension enumerates the whole library and the ledger skips already-recorded keys
