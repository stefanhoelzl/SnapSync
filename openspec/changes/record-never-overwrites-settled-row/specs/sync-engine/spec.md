## MODIFIED Requirements

### Requirement: Failure adjudication — retry forever
When a platform submits `UploadFailed(job, error)`, the engine SHALL answer `Retry` carrying one
fresh `UploadJob` with `attempt` incremented by one and a request newly minted via
`provide(job.request.resource)` — for every `UploadError` variant, with no attempt budget — and
SHALL record `FAILED` (the failed attempt) for the key. The engine SHALL NOT record `REQUESTED` for
the retry here; the ledger is left in `FAILED` until the platform creates the retry job and reports
`UploadStarted` (write-after-act). Recording `FAILED` is an idempotent **guarded** upsert: it SHALL NOT
overwrite a row in a done state (capability `sync-ledger`, "Record operations"). The answer SHALL be
`Retry` whether or not the record applied — the engine's decision does not depend on the guard.

#### Scenario: Fresh request on retry, ledger left FAILED
- **WHEN** `handle(UploadFailed(job, Http(403)))` is called
- **THEN** `Retry` is returned with `attempt == job.attempt + 1` and a request newly obtained from
  the provider for the same resource instance, and the ledger entry for the key is `FAILED` with the
  failed attempt (the new `REQUESTED` is written only when the platform reports `UploadStarted`)

#### Scenario: Every error kind retries
- **WHEN** failures with `Network`, `Http(500)`, `Cancelled`, and `Unknown("x")` are each handled
- **THEN** each yields exactly one `Retry` — none is dropped

#### Scenario: A late failure never un-completes a key
- **WHEN** `handle(UploadFailed(job, Network))` is called for a key whose ledger entry is `COMPLETED`
- **THEN** `Retry` is still returned, and the ledger entry is unchanged — still `COMPLETED` with its prior
  attempt

### Requirement: Upload-started recording (write-after-act)
The engine SHALL accept a `SyncEvent.UploadStarted(job)` observation, reported by the platform
**after** it has created (or retried) the upload job for `job`. On `UploadStarted` the engine SHALL
record `REQUESTED` for the key with `job.attempt`, and SHALL answer `AlreadyUploaded` (there is
nothing further for the platform to do). `REQUESTED` SHALL be recorded **only** on `UploadStarted` —
never on `ResourceChanged` or `UploadFailed`. Recording is an idempotent per-key **guarded** upsert that
SHALL NOT overwrite a row in a done state (capability `sync-ledger`, "Record operations"),
so a duplicated or replayed `UploadStarted` converges to the same entry and a late one never un-completes a key;
a dropped `UploadStarted`
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

#### Scenario: A late start never un-completes a key
- **WHEN** `handle(UploadStarted(job))` is called for a key whose ledger entry is `COMPLETED`
- **THEN** `AlreadyUploaded` is returned and the ledger entry is unchanged
