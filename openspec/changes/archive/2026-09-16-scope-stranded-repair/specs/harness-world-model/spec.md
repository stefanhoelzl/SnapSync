## MODIFIED Requirements

### Requirement: Operator-driven, inspectable upload-job lifecycle

The world SHALL provide a fake `BackgroundTransfer` that models the OS upload-job lifecycle as an
operator-driven, **inspectable** queue implementing every seam method. Like the device transports, it SHALL
receive the ledger only as a `TransferRecord` (`sync-ledger`) and SHALL record each terminal outcome through
its guarded `markTerminal`; it SHALL serve no library read. Like the OS-driven queue it models, it SHALL
report the absence of a live set from `liveKeys()` and of a lost set from `lostKeys()`, and hold nothing for
`discard` to drop, so the world runs no stranded reconciliation.
`createJob` SHALL enqueue a PENDING job and return `CREATED`, unless a **settable job-limit** is reached
(returning `LIMIT_EXCEEDED`) or a forced create-failure is set (returning `FAILED`). An operator **complete**
action SHALL deposit the job's object key into the backend object store **store-direct** (byte transfer
is not routed through ktor) and move the job to the acknowledge bucket, so the next cycle records it
`COMPLETED`. An operator **fail** action SHALL move the job to the retry bucket carrying a chosen engine
`UploadError` (`Network`, `Http`, `Cancelled`, or `Unknown`), driving the real engine retry chain with
an incremented attempt. The queue's pending/retry/acknowledge buckets and per-job attempt SHALL be
inspectable so tests assert the lifecycle, not only the final outcome.

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

#### Scenario: The fake queue reports neither live nor lost transfers

- **WHEN** the world's upload cycle reaches its stranded pass
- **THEN** the fake queue reports no live set and no lost set, so no row is recorded `FAILED` by that pass

#### Scenario: The fake queue holds no ledger store

- **WHEN** the world constructs its fake job queue
- **THEN** it is given the world's ledger as a `TransferRecord`, exactly as a device transport is
