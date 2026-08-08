## MODIFIED Requirements

### Requirement: Background-URLSession BackgroundTransfer implementation

The app-driven tier SHALL implement the existing `BackgroundTransfer` port (`:domain` `ports/`) with a
background-`URLSession`-backed adapter (`IosUrlSessionUploadPlatform`) — **not** a new seam — so
`UploadCycle` runs unchanged. The adapter SHALL map the seam verbs to `URLSession` semantics:

- `createJob(request, resource)` SHALL start a background `uploadTask(fromFile:)` for the staged
  resource, tag the task with the ledger key via `taskDescription`, and return `CREATED`; when the
  adapter's own in-flight concurrency cap is already reached it SHALL return `LIMIT_EXCEEDED` (the
  adapter's backpressure), and on a failure to start (e.g. unusable staged file) it SHALL return
  `FAILED`.
- `fetchRetryJobs()` SHALL return an **empty** list — this platform grants no OS-sponsored single
  retry; terminal failures are surfaced through `fetchAckJobs()` as retry-spent and recreated by
  `UploadCycle`.
- `fetchAckJobs()` SHALL return terminal jobs recovered from delivered `URLSession` delegate
  completions (success and failure/cancellation), keyed by the task's destination-URL last path
  segment (mirroring the PhotoKit key recovery). Each surfaced job SHALL carry the **content type its
  request was created with**, which the adapter holds in its own in-flight task record; it SHALL NOT
  substitute a fixed placeholder such as `application/octet-stream`. `UploadCycle` rebuilds a retried
  job's `Resource` from the key alone, so a placeholder here is not inert — it becomes the type the
  recreated upload is stored under, and the object is mistyped for the rest of its life.
- `retryJob(job, request)` SHALL be implemented as cancel-and-recreate.
- `acknowledge(job)` SHALL perform local cleanup — drop the adapter's task record and delete the
  resource's staged temp file — with no OS acknowledgement (there is no system slot to free).
- `discoverResources(sinceToken)` SHALL reuse the shared change-token walk (`IosDiscovery`), identical
  to the PhotoKit tier.

Correctness SHALL rely on **at-least-once** delivery: keys are deterministic and the edge PUT is
idempotent (a re-PUT overwrites the same object), so a duplicate send is harmless and the ledger
remains the source of truth.

#### Scenario: fetchRetryJobs is empty on this platform
- **WHEN** `UploadCycle` calls `fetchRetryJobs()` on the `URLSession` adapter
- **THEN** it returns an empty list, and a failed upload instead appears via `fetchAckJobs()` as a terminal (retry-spent) job that `UploadCycle` recreates

#### Scenario: A recreated upload keeps its original content type
- **WHEN** a terminal job is surfaced through `fetchAckJobs()` and `UploadCycle` recreates it
- **THEN** the recreated request carries the content type of the original request, so the stored object
  is typed identically on this tier and on the PhotoKit tier

#### Scenario: Own cap surfaces as LIMIT_EXCEEDED
- **WHEN** `createJob` is called while the adapter already has its cap of tasks in flight
- **THEN** it returns `LIMIT_EXCEEDED`, so `UploadCycle` returns `PROCESSING` and the pump re-arms

#### Scenario: acknowledge cleans up local state
- **WHEN** `acknowledge(job)` is called for a completed job
- **THEN** the adapter drops the task record and deletes the resource's staged temp file, making no OS call
