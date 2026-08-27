## MODIFIED Requirements

### Requirement: Operator-driven, inspectable upload-job lifecycle

The world SHALL provide a fake `BackgroundTransfer` that models the OS upload-job lifecycle as an
operator-driven, **inspectable** queue implementing every seam method, including the resolution of
ledger keys to uploadable resources (capability `ios-url-session-upload`). `createJob` SHALL enqueue
a PENDING job and return `CREATED`, unless a **settable job-limit** is reached (returning
`LIMIT_EXCEEDED`) or a forced create-failure is set (returning `FAILED`). An operator **complete**
action SHALL deposit the job's object key into the backend object store **store-direct** (byte transfer
is not routed through ktor) and move the job to the acknowledge bucket, so the next cycle records it
`COMPLETED`. An operator **fail** action SHALL move the job to the retry bucket carrying a chosen engine
`UploadError` (`Network`, `Http`, `Cancelled`, or `Unknown`), driving the real engine retry chain with
an incremented attempt. The queue's pending/retry/acknowledge buckets and per-job attempt SHALL be
inspectable so tests assert the lifecycle, not only the final outcome.

The key resolution SHALL be served from the world's in-memory gallery and SHALL be observable, so a
test can assert **which keys** a cycle resolved — the evidence that it enqueued from the ledger rather
than from the walk's output. The discovery feed's consumption SHALL be observable for the same reason.
A key whose asset the operator has removed from the gallery SHALL resolve to nothing.

#### Scenario: Complete deposits the object and the ledger records COMPLETED

- **WHEN** the operator completes a created job and the next upload cycle runs
- **THEN** the object key is present in the backend store and the ledger holds a `COMPLETED` row for it

#### Scenario: Fail drives the real retry chain

- **WHEN** the operator fails a created job with a chosen `UploadError` and the next cycle runs
- **THEN** the engine answers `Retry`, the job is re-created, and its attempt count increments

#### Scenario: Job-limit truncates creation but not the cycle

- **WHEN** the job-limit is set below the number of `Work` resources in a cycle
- **THEN** `createJob` returns `LIMIT_EXCEEDED`, the cycle returns `PROCESSING`, the un-enqueued
  resources hold `DISCOVERED` rows, the discovery cursor **has** advanced, and the cycle still
  published its device manifest

#### Scenario: A later cycle enqueues the remainder from the ledger

- **WHEN** a job-limited cycle is followed by another cycle with no gallery change in between
- **THEN** the remainder is enqueued by resolving its `DISCOVERED` rows' keys, not from anything the
  change feed returned — which reports nothing, since nothing changed

#### Scenario: A removed asset resolves to nothing

- **WHEN** the operator removes an asset from the gallery and a cycle resolves a ledger key for it
- **THEN** the resolution returns nothing for that key
