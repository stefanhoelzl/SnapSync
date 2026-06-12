# sync-engine — delta spec

## ADDED Requirements

### Requirement: Resource-changed decision
When a platform submits `ResourceChanged(resource)`, the engine SHALL return exactly one
`UploadJob` with `attempt = 0` whose request is the provider's return value for that resource.

#### Scenario: One job per event
- **WHEN** `handle(ResourceChanged(resource))` is called
- **THEN** one `UploadJob` is returned with `attempt == 0` and `request` exactly as the provider
  returned it

#### Scenario: Resource instance round-trips
- **WHEN** a job is returned for a resource
- **THEN** `job.request.resource` is the identical instance the platform supplied (no copying), so
  the platform can read its opaque `data` payload back at the execution edge

### Requirement: Request minting via the request provider
For every returned job the engine SHALL obtain the request by calling
`UploadRequestProvider.provide(resource)` with the platform's resource instance, and SHALL carry
the returned `UploadRequest` on the job unmodified. Encoding and placement of the filename are
the provider's responsibility; the seam SHALL document the provider contract that the
filename→destination mapping is deterministic and injective.

#### Scenario: Provider receives the resource
- **WHEN** `handle(ResourceChanged(resource))` is called
- **THEN** the provider was invoked exactly once with that same resource instance, and
  `job.request` is its return value, unmodified

### Requirement: Failure adjudication — retry forever
When a platform submits `UploadFailed(job, error)`, the engine SHALL return exactly one fresh
`UploadJob` with `attempt` incremented by one and a request newly minted via
`provide(job.request.resource)` — for every `UploadError` variant, with no attempt budget.

#### Scenario: Fresh request on retry
- **WHEN** `handle(UploadFailed(job, Http(403)))` is called
- **THEN** one job is returned with `attempt == job.attempt + 1` and a request newly obtained
  from the provider for the same resource instance (not the failed job's request)

#### Scenario: Every error kind retries
- **WHEN** failures with `Network`, `Http(500)`, `Cancelled`, and `Unknown("x")` are each handled
- **THEN** each yields exactly one retry job — none is dropped

### Requirement: Provider failures rethrow
If the request provider throws, the engine SHALL NOT catch it: `handle` fails with that
exception. The event counts as unprocessed and re-handling the same event later is safe (the
engine holds no state).

#### Scenario: Provider failure propagates
- **WHEN** the provider throws during `handle(ResourceChanged(resource))`
- **THEN** the caller receives the provider's exception unswallowed, and a subsequent
  `handle` of the same event succeeds when the provider does

### Requirement: Concurrent calls
`handle` MAY be called concurrently; the engine SHALL hold no shared mutable state. Correctness
under concurrency is conditional only on the provider tolerating concurrent `provide` calls.

#### Scenario: Two events in flight
- **WHEN** two `handle` calls for different resources run concurrently against a thread-safe
  provider
- **THEN** each returns exactly its own resource's job, with no cross-talk or corruption
