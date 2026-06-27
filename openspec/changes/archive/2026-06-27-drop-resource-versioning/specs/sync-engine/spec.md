## MODIFIED Requirements

### Requirement: Resource-changed decision
When a platform submits `ResourceChanged(resource)`, the engine SHALL answer one `SyncDecision`
derived from the ledger entry for `resource.filename`, and SHALL **write nothing** — `handle` of a
`ResourceChanged` is a pure query (it reads the ledger and mints a request for `Work` answers, but
recording `REQUESTED` happens only on a later `UploadStarted`, see "Upload-started recording"):

- entry `COMPLETED` or `REQUESTED` → `AlreadyUploaded` (no job). An uploaded resource is
  **immutable**, so a `COMPLETED` key is backed up for good and never re-uploaded; a `REQUESTED` key
  has a job in flight whose eventual `UploadStarted`/`UploadFailed`/`UploadCompleted` the engine
  relies on. In neither case SHALL the engine re-issue work.
- entry `FAILED`, or entry absent → `Upload` carrying an `UploadJob` with `attempt = 0`

Only a new key (absent entry) or a previously failed one yields `Work`; there is no content-version
comparison and no re-upload of an existing key. `Upload` and `Retry` SHALL implement a common `Work`
interface exposing the job, so platforms execute all work arms identically. For `AlreadyUploaded` the
ledger SHALL be left untouched (the same as every `ResourceChanged` answer, which never writes).

#### Scenario: Unknown resource uploads without writing the ledger
- **WHEN** `handle(ResourceChanged(resource))` is called and the ledger has no entry for its
  filename
- **THEN** `Upload` is returned with `attempt == 0` and the ledger still has no entry for the key
  (recording is deferred to `UploadStarted`)

#### Scenario: Completed key skips
- **WHEN** the ledger entry is `COMPLETED`
- **THEN** `AlreadyUploaded` is returned, no provider call is made, and the ledger entry is
  unchanged

#### Scenario: In-flight request skips on re-submission
- **WHEN** the ledger entry is `REQUESTED` and the same resource is re-submitted as `ResourceChanged`
- **THEN** `AlreadyUploaded` is returned, no provider call is made, and the ledger is unchanged — an
  in-flight job is not duplicated

#### Scenario: Failed entry re-uploads
- **WHEN** the ledger entry is `FAILED` and the same resource is submitted as `ResourceChanged`
- **THEN** `Upload` is returned with `attempt == 0` and nothing is written until `UploadStarted`

#### Scenario: Resource instance round-trips
- **WHEN** a `Work` decision is returned for a resource
- **THEN** `decision.job.request.resource` is the identical instance the platform supplied (no
  copying), so the platform can read its opaque `data` payload back at the execution edge

### Requirement: Completion recording
When a platform submits `UploadCompleted(job)`, the engine SHALL record `COMPLETED` for the key
(with the job's attempt) and answer `AlreadyUploaded`. The report is made at the platform's
acknowledge edge, before acknowledging. Completion reports arrive at-least-once; duplicates SHALL
converge to the same ledger entry.

#### Scenario: Completion marks the key done
- **WHEN** `handle(UploadCompleted(job))` is called
- **THEN** `AlreadyUploaded` is returned and the ledger entry is `COMPLETED` with the job's attempt

#### Scenario: Duplicate completion is a no-op
- **WHEN** the same `UploadCompleted(job)` is handled twice
- **THEN** the second answer is also `AlreadyUploaded` and the ledger entry is unchanged

#### Scenario: Completed key skips thereafter
- **WHEN** a completion is recorded and the same resource is later re-submitted as `ResourceChanged`
- **THEN** the answer is `AlreadyUploaded`

### Requirement: Upload-started recording (write-after-act)
The engine SHALL accept a `SyncEvent.UploadStarted(job)` observation, reported by the platform
**after** it has created (or retried) the upload job for `job`. On `UploadStarted` the engine SHALL
record `REQUESTED` for the key with `job.attempt`, and SHALL answer `AlreadyUploaded` (there is
nothing further for the platform to do). `REQUESTED` SHALL be recorded **only** on `UploadStarted` —
never on `ResourceChanged` or `UploadFailed`. Recording is an unconditional idempotent per-key upsert,
so a duplicated or replayed `UploadStarted` converges to the same entry; a dropped `UploadStarted`
(the platform created the job but died before reporting) leaves no `REQUESTED`, which a later
`ResourceChanged` re-derivation safely re-issues as `Work`.

#### Scenario: Created job records REQUESTED
- **WHEN** `handle(UploadStarted(job))` is called for a key with no entry (or a `FAILED` entry)
- **THEN** the ledger entry becomes `REQUESTED` with `job.attempt`, and `AlreadyUploaded` is returned

#### Scenario: Decision then act then record converge
- **WHEN** `ResourceChanged` yields `Upload` (no write), the platform creates the job, and reports
  `UploadStarted(job)`
- **THEN** the ledger holds `REQUESTED` exactly once for the key, regardless of how many times the
  `ResourceChanged`→`UploadStarted` pair is replayed

#### Scenario: Dropped UploadStarted is re-issued, not stranded
- **WHEN** an `Upload` decision is acted on but its `UploadStarted` is never delivered, and the same
  resource is later re-submitted as `ResourceChanged`
- **THEN** the engine returns `Work` again (the key has no `REQUESTED`), so the create is retried
  rather than skipped

### Requirement: Resource asset identity
`Resource` SHALL carry `assetId: String` — an opaque grouping identifier for the asset a resource
belongs to (several resources of one photo share it). The engine SHALL carry `assetId` through to
the ledger (via the record operations) but SHALL NOT interpret it — like `filename`, it is pure
identity whose meaning belongs to the platform (iOS: the asset's `localIdentifier`, normalized;
tests/console: any string). It plays no part in the decision: a `ResourceChanged` is still decided
solely from the ledger entry for `filename`.

#### Scenario: assetId is carried into the recorded entry
- **WHEN** a resource with `assetId = "A"` is uploaded and the platform reports `UploadStarted`
- **THEN** the ledger entry for its key has `assetId == "A"`

#### Scenario: assetId does not change the decision
- **WHEN** a `ResourceChanged` is handled for a resource whose key is absent from the ledger
- **THEN** the answer is `Upload` regardless of the resource's `assetId` (the decision reads only
  `filename`)

## REMOVED Requirements

### Requirement: Resource version
**Reason**: Uploaded resources are now immutable — the engine never compares content versions. A
`COMPLETED` key is backed up for good; only new (absent) or `FAILED` keys produce `Work`. `Resource`
no longer carries `version` and `SyncDecision.ReUpload` no longer exists.
**Migration**: The behavior the version comparison provided (skip a re-seen resource) is subsumed by
the state-only "Resource-changed decision": `COMPLETED`/`REQUESTED` → `AlreadyUploaded`. There is no
replacement signal; re-editing an already-uploaded resource is an accepted blind spot.
