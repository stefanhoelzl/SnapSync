## MODIFIED Requirements

### Requirement: Resource-changed decision
When a platform submits `ResourceChanged(resource)`, the engine SHALL answer one `SyncDecision`
derived from the ledger entry for `resource.filename`, and SHALL **write nothing** — `handle` of a
`ResourceChanged` is a pure query (it reads the ledger and mints a request for `Work` answers, but
recording `REQUESTED` happens only on a later `UploadStarted`, see "Upload-started recording"):

- entry `COMPLETED` or `REQUESTED` with `version == resource.version` → `AlreadyUploaded` (no job).
  A `REQUESTED` entry means an upload job for this content is already in flight; the engine relies on
  the platform to report its eventual `UploadStarted`/`UploadFailed`/`UploadCompleted`, so the engine
  SHALL NOT re-issue work for it.
- entry `FAILED`, or entry absent → `Upload` carrying an `UploadJob` with `attempt = 0`
- entry `COMPLETED` or `REQUESTED` with `version != resource.version` → `ReUpload` carrying an
  `UploadJob` with `attempt = 0` (the content changed; a new generation supersedes the in-flight one)

`Upload`, `ReUpload`, and `Retry` SHALL implement a common `Work` interface exposing the job, so
platforms execute all work arms identically. For `AlreadyUploaded` the ledger SHALL be left
untouched (the same as every `ResourceChanged` answer, which never writes).

#### Scenario: Unknown resource uploads without writing the ledger
- **WHEN** `handle(ResourceChanged(resource))` is called and the ledger has no entry for its
  filename
- **THEN** `Upload` is returned with `attempt == 0` and the ledger still has no entry for the key
  (recording is deferred to `UploadStarted`)

#### Scenario: Completed and unchanged skips
- **WHEN** the ledger entry is `COMPLETED` with the same version as the incoming resource
- **THEN** `AlreadyUploaded` is returned, no provider call is made, and the ledger entry is
  unchanged

#### Scenario: In-flight request skips on re-submission
- **WHEN** the ledger entry is `REQUESTED` with the same version and the same resource is
  re-submitted as `ResourceChanged`
- **THEN** `AlreadyUploaded` is returned, no provider call is made, and the ledger is unchanged — an
  in-flight job is not duplicated

#### Scenario: Failed entry re-uploads
- **WHEN** the ledger entry is `FAILED` and the same resource is submitted as `ResourceChanged`
- **THEN** `Upload` is returned with `attempt == 0` and nothing is written until `UploadStarted`

#### Scenario: Completed but changed re-uploads
- **WHEN** the ledger entry is `COMPLETED` (or `REQUESTED`) with a version differing from the
  incoming resource
- **THEN** `ReUpload` is returned with `attempt == 0`

#### Scenario: Resource instance round-trips
- **WHEN** a `Work` decision is returned for a resource
- **THEN** `decision.job.request.resource` is the identical instance the platform supplied (no
  copying), so the platform can read its opaque `data` payload back at the execution edge

### Requirement: Failure adjudication — retry forever
When a platform submits `UploadFailed(job, error)`, the engine SHALL answer `Retry` carrying one
fresh `UploadJob` with `attempt` incremented by one and a request newly minted via
`provide(job.request.resource)` — for every `UploadError` variant, with no attempt budget — and
SHALL record `FAILED` (the failed attempt) for the key. The engine SHALL NOT record `REQUESTED` for
the retry here; the ledger is left in `FAILED` until the platform creates the retry job and reports
`UploadStarted` (write-after-act). Recording `FAILED` is an unconditional idempotent upsert.

#### Scenario: Fresh request on retry, ledger left FAILED
- **WHEN** `handle(UploadFailed(job, Http(403)))` is called
- **THEN** `Retry` is returned with `attempt == job.attempt + 1` and a request newly obtained from
  the provider for the same resource instance, and the ledger entry for the key is `FAILED` with the
  failed attempt (the new `REQUESTED` is written only when the platform reports `UploadStarted`)

#### Scenario: Every error kind retries
- **WHEN** failures with `Network`, `Http(500)`, `Cancelled`, and `Unknown("x")` are each handled
- **THEN** each yields exactly one `Retry` — none is dropped

## ADDED Requirements

### Requirement: Upload-started recording (write-after-act)
The engine SHALL accept a `SyncEvent.UploadStarted(job)` observation, reported by the platform
**after** it has created (or retried) the upload job for `job`. On `UploadStarted` the engine SHALL
record `REQUESTED` for the key with `job.attempt` and the job's resource version, and SHALL answer
`AlreadyUploaded` (there is nothing further for the platform to do). `REQUESTED` SHALL be recorded
**only** on `UploadStarted` — never on `ResourceChanged` or `UploadFailed`. Recording is an
unconditional idempotent per-key upsert, so a duplicated or replayed `UploadStarted` converges to the
same entry; a dropped `UploadStarted` (the platform created the job but died before reporting) leaves
no `REQUESTED`, which a later `ResourceChanged` re-derivation safely re-issues as `Work`.

#### Scenario: Created job records REQUESTED
- **WHEN** `handle(UploadStarted(job))` is called for a key with no entry (or a `FAILED` entry)
- **THEN** the ledger entry becomes `REQUESTED` with `job.attempt` and the job's resource version,
  and `AlreadyUploaded` is returned

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
