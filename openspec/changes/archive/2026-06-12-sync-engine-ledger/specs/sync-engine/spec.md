# sync engine Specification (delta)

## MODIFIED Requirements

### Requirement: Resource-changed decision
When a platform submits `ResourceChanged(resource)`, the engine SHALL answer one `SyncDecision`
derived from the ledger entry for `resource.filename`:

- entry absent, `REQUESTED`, or `FAILED` → `Upload` carrying an `UploadJob` with `attempt = 0`
  (a `REQUESTED` entry is a hope — the engine cannot prove the platform executed it — and SHALL
  never justify skipping)
- entry `COMPLETED` with `version == resource.version` → `AlreadyUploaded` (no job)
- entry `COMPLETED` with `version != resource.version` → `ReUpload` carrying an `UploadJob` with
  `attempt = 0`

For every `Work` decision the engine SHALL record `REQUESTED` (attempt, `resource.version`) in
the ledger. For `AlreadyUploaded` the ledger SHALL be left untouched. `Upload`, `ReUpload`, and
`Retry` SHALL implement a common `Work` interface exposing the job, so platforms execute all
work arms identically.

#### Scenario: Unknown resource uploads
- **WHEN** `handle(ResourceChanged(resource))` is called and the ledger has no entry for its
  filename
- **THEN** `Upload` is returned with `attempt == 0` and the ledger records `REQUESTED` with the
  resource's version

#### Scenario: Completed and unchanged skips
- **WHEN** the ledger entry is `COMPLETED` with the same version as the incoming resource
- **THEN** `AlreadyUploaded` is returned, no provider call is made, and the ledger entry is
  unchanged

#### Scenario: Completed but changed re-uploads
- **WHEN** the ledger entry is `COMPLETED` with a version differing from the incoming resource
- **THEN** `ReUpload` is returned with `attempt == 0` and the ledger records `REQUESTED` with the
  new version

#### Scenario: A hope never skips
- **WHEN** the ledger entry is `REQUESTED` (or `FAILED`) and the same resource is re-submitted
- **THEN** `Upload` is returned again — re-submission of unproven work always yields work

#### Scenario: Resource instance round-trips
- **WHEN** a `Work` decision is returned for a resource
- **THEN** `decision.job.request.resource` is the identical instance the platform supplied (no
  copying), so the platform can read its opaque `data` payload back at the execution edge

### Requirement: Request minting via the request provider
For every `Work` decision the engine SHALL obtain the request by calling
`UploadRequestProvider.provide(resource)` with the platform's resource instance, and SHALL carry
the returned `UploadRequest` on the job unmodified. The provider SHALL NOT be called when the
answer is `AlreadyUploaded`. Encoding and placement of the filename remain the provider's
responsibility under the deterministic-and-injective filename→destination contract.

#### Scenario: Provider receives the resource
- **WHEN** `handle(ResourceChanged(resource))` yields a `Work` decision
- **THEN** the provider was invoked exactly once with that same resource instance, and
  `job.request` is its return value, unmodified

#### Scenario: No minting for skipped work
- **WHEN** `handle(ResourceChanged(resource))` yields `AlreadyUploaded`
- **THEN** the provider was not invoked

### Requirement: Failure adjudication — retry forever
When a platform submits `UploadFailed(job, error)`, the engine SHALL answer `Retry` carrying one
fresh `UploadJob` with `attempt` incremented by one and a request newly minted via
`provide(job.request.resource)` — for every `UploadError` variant, with no attempt budget — and
SHALL record `FAILED` (failed attempt) then `REQUESTED` (new attempt) for the key, leaving the
ledger in `REQUESTED` state.

#### Scenario: Fresh request on retry
- **WHEN** `handle(UploadFailed(job, Http(403)))` is called
- **THEN** `Retry` is returned with `attempt == job.attempt + 1` and a request newly obtained from
  the provider for the same resource instance, and the ledger entry for the key is `REQUESTED`
  with the new attempt

#### Scenario: Every error kind retries
- **WHEN** failures with `Network`, `Http(500)`, `Cancelled`, and `Unknown("x")` are each handled
- **THEN** each yields exactly one `Retry` — none is dropped

### Requirement: Provider failures rethrow
If the request provider throws, the engine SHALL NOT catch it: `handle` fails with that exception
and the ledger SHALL be left unchanged for that event (the engine records only after minting
succeeds). The event counts as unprocessed; re-handling the same event later is safe because
recording is an idempotent per-key upsert.

#### Scenario: Provider failure propagates and leaves no trace
- **WHEN** the provider throws during `handle(ResourceChanged(resource))` for a key with no ledger
  entry
- **THEN** the caller receives the provider's exception unswallowed, the ledger still has no entry
  for the key, and a subsequent `handle` of the same event succeeds when the provider does

## ADDED Requirements

### Requirement: Completion recording
When a platform submits `UploadCompleted(job)` — reported at the platform's acknowledge edge,
before acknowledging — the engine SHALL record `COMPLETED` for the key (with the job's attempt
and the resource's version) and answer `AlreadyUploaded`. Completion reports arrive at-least-once;
duplicates SHALL converge to the same ledger entry.

#### Scenario: Completion marks the key done
- **WHEN** `handle(UploadCompleted(job))` is called
- **THEN** `AlreadyUploaded` is returned and the ledger entry is `COMPLETED` with the job's
  attempt and its resource's version

#### Scenario: Duplicate completion is a no-op
- **WHEN** the same `UploadCompleted(job)` is handled twice
- **THEN** the second answer is also `AlreadyUploaded` and the ledger entry is unchanged

#### Scenario: Completed key skips thereafter
- **WHEN** a completion is recorded and the same resource (same version) is later re-submitted as
  `ResourceChanged`
- **THEN** the answer is `AlreadyUploaded`

### Requirement: Resource version
`Resource` SHALL carry `version: String` — the platform's proof of content identity (e.g. the
asset's modification date on iOS). The engine SHALL compare versions for equality only, never
parse them.

#### Scenario: Version drives re-upload, not its content
- **WHEN** two resources differ only in `version` (any two distinct strings)
- **THEN** the second submitted after the first completes yields `ReUpload` — the strings'
  structure is irrelevant

## REMOVED Requirements

### Requirement: Concurrent calls
**Reason**: Concurrency was free under statelessness; with the ledger it would cost transactions
or SQL-encoded precedence, and no existing or planned driver submits concurrently (extension
`processJobs()` and the engine console are sequential loops).
**Migration**: Callers SHALL serialize: at most one `handle()` call in flight per engine instance.
A future concurrent driver reintroduces the guarantee in its own slice.
