## MODIFIED Requirements

### Requirement: Disabling the extension clears orphaned REQUESTED rows

The app SHALL recover the in-flight jobs wiped by a disable. Disabling the upload extension
(`setUploadJobExtensionEnabled(false)`) deletes the system's `AssetResourceUploadJobConfiguration` and
therefore **wipes every in-flight OS upload job**. Whenever
the app disables the extension it SHALL, immediately after the disable, **both** (a) call the ledger's
`clearRequested()` (`sync-ledger`) to drop the now-orphaned `REQUESTED` rows, and (b) **reset the
discovery cursor** (clear the App-Group change-token) so the next cycle does a **full re-enumeration**.
Both are required: `clearRequested()` only makes the keys *absent*, but a settled cursor scans
incrementally and would never re-surface them — so without the cursor reset the cleared photos are
re-discovered only when the library next changes. This SHALL apply to **both** disable paths: the
disable half of the `disable→enable` re-register, and the leave use-case's extension-disable.

The disable-and-clear SHALL be **awaited off the main thread and completed before any re-enable**. The
`clearRequested()` write SHALL run on `Dispatchers.Default` (Kotlin/Native has no `Dispatchers.IO`),
never on the `Dispatchers.Main` scope — it is a synchronous SQLite `DELETE` that on the main thread is
a hang risk under cross-process WAL contention — and SHALL use a small bounded retry around the write.
The `disable→enable` re-register SHALL NOT call `setUploadJobExtensionEnabled(true)` until the clear
has completed, so the re-enabled extension's freshly recorded `REQUESTED` rows can never be deleted by
a still-running clear. The clear SHALL NOT be fire-and-forget. The bounded-retry, off-main clear is
pure logic and SHALL live in a tested `domain`/`capability` helper injected into both disable paths,
not in the untested app shell; only the sequencing of the two iOS platform calls remains in the shell.

Without `clearRequested()`, the rows stay `REQUESTED` forever: the engine treats `REQUESTED` as
in-flight and never re-issues it, there is no API to enumerate live jobs to detect that the job is
gone, and a same-event cycle never reconciles — so the photos that were mid-upload at the disable are
permanently abandoned. With both clears, the next full enumeration re-discovers the cleared keys and
re-creates exactly the not-yet-stored jobs (stored files remain `COMPLETED` and are skipped). The app
SHALL route both disable paths through a single helper so they cannot diverge, and SHALL use the
`LedgerBackend` directly (constructing no `LedgerWriter`), since `clearRequested` is an app-side
reset-family operation.

#### Scenario: A re-register self-heals instead of orphaning

- **WHEN** photos are mid-upload (`REQUESTED` rows, OS jobs registered, the discovery cursor settled)
  and the app re-registers the extension (disable→enable)
- **THEN** the disable wipes the OS jobs, `clearRequested()` drops the `REQUESTED` rows, and the
  discovery cursor is reset — so the next cycle's full re-enumeration re-discovers and re-creates the
  not-yet-stored jobs (bytes resume landing), with no permanently-stuck `REQUESTED`

#### Scenario: The re-enable does not race the clear

- **WHEN** the app re-registers the extension (disable→enable)
- **THEN** `clearRequested()` runs off-main and completes **before** `setUploadJobExtensionEnabled(true)`
  is called, so no `REQUESTED` row recorded by the re-enabled extension is deleted by the clear

#### Scenario: The clear runs off the main thread

- **WHEN** a disable triggers `clearRequested()`
- **THEN** the SQLite delete executes on `Dispatchers.Default` (not the `Dispatchers.Main` scope) with
  a bounded retry, and is awaited rather than launched fire-and-forget

#### Scenario: Leave clears REQUESTED

- **WHEN** the leave use-case disables the extension while resources are `REQUESTED`
- **THEN** `clearRequested()` runs as part of the disable, leaving no orphaned `REQUESTED` rows behind

#### Scenario: Completed rows survive the clear

- **WHEN** a disable triggers `clearRequested()` and the ledger holds `COMPLETED` rows for
  already-stored files
- **THEN** those `COMPLETED` rows are retained, so a subsequent reconcile/discovery does not re-upload
  already-stored bytes

### Requirement: Completion and retry adjudication

The extension SHALL adjudicate the system's returned upload jobs each cycle, **before** discovering
new work (so completed/failed slots are freed first), and reduce each outcome into the engine. It
SHALL recover a returned `PHAssetResourceUploadJob`'s ledger key from the job's **destination URL**
(the last path segment) — the only field reliably present for every job state, since `resource` is
**nil for succeeded jobs** (the system releases it after upload). Version/attempt come from the
ledger (`LedgerReader`); the `resource`, when still present, is reused only to re-create a
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

### Requirement: Discovery suppresses downloaded assets

The upload cycle's discovery SHALL consult the download store's suppression projection (the set of
`createdLocalId`s of foreign assets this device downloaded and imported) and SHALL drop every
discovered resource whose `assetId` — **normalized `'/'→'_'` to match the stored `createdLocalId`
form** — is in that set **before** engine fan-out (no upload job created)
and before `retainAssets`. This prevents the download→import→re-upload echo: an imported foreign asset
gets a fresh local `localIdentifier` that discovery would otherwise treat as a new local asset and
upload back. The normalization SHALL be the **same** transform the shared gallery enumeration applies
when deriving the upload key, so the two sides meet byte-for-byte. The suppression read SHALL be
read-only and cross-process (the extension reads the
app-written store over WAL). The filter SHALL live in the platform-free upload-cycle core (a injected
suppression port), not in untested platform wiring, so it is exercised in `commonTest`.

#### Scenario: A downloaded-then-imported asset is never re-uploaded

- **WHEN** discovery encounters a resource whose `assetId` (normalized `'/'→'_'`) is in the
  suppression set
- **THEN** no upload job is created for it and it is excluded from `retainAssets`

#### Scenario: Suppression is consulted before fan-out

- **WHEN** a discovery cycle runs
- **THEN** suppressed assets are removed from the discovered set before the engine is asked to create
  any upload job

#### Scenario: Suppression matching normalizes the assetId

- **WHEN** a discovered resource's raw `assetId` contains `'/'` and the stored `createdLocalId` is its
  `'/'→'_'` normalized form
- **THEN** the two are treated as the same identity and the resource is suppressed

#### Scenario: Non-suppressed assets upload normally

- **WHEN** discovery encounters a resource whose `assetId` is not suppressed
- **THEN** it is handed to the engine and uploaded as before
