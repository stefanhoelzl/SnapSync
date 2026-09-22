## MODIFIED Requirements

### Requirement: Operator-driven, inspectable upload-job lifecycle

The world SHALL provide a fake `BackgroundTransfer` that models the OS upload-job lifecycle as an
operator-driven, **inspectable** queue implementing every seam method. Like the device transports, it SHALL
receive the ledger only as a `TransferRecord` (`sync-ledger`) and SHALL record each terminal outcome through
its guarded `markTerminal`; it SHALL serve no library read. Like every transport, it SHALL expose no capacity
read and no live-set, lost-set or discard member: the cycle creates until `createJob` refuses, and no cycle
reconciles stranded rows (decision record: `changes/both-uploaders-active`).
`createJob` SHALL enqueue a PENDING job and return `CREATED`, unless a **settable job-limit** is reached
(returning `LIMIT_EXCEEDED`) or a forced create-failure is set (returning `FAILED`). An operator **complete**
action SHALL deposit the job's object key into the backend object store **store-direct** (byte transfer
is not routed through ktor) and move the job to the acknowledge bucket, so the next cycle records it
`COMPLETED`. An operator **fail** action SHALL move the job to the retry bucket carrying a chosen engine
`UploadError` (`Network`, `Http`, `Cancelled`, or `Unknown`), driving the real engine retry chain: the
engine answers `Retry` with a freshly minted request and the job is re-created. The engine carries no attempt
count (capability `sync-engine`), so the queue records its own per-key count of the creations it observed. The
queue's pending/retry/acknowledge buckets and per-key creation count SHALL be
inspectable so tests assert the lifecycle, not only the final outcome.

#### Scenario: Complete deposits the object and the ledger records COMPLETED

- **WHEN** the operator completes a created job and the next upload cycle runs
- **THEN** the object key is present in the backend store and the ledger holds a `COMPLETED` row for it

#### Scenario: Fail drives the real retry chain

- **WHEN** the operator fails a created job with a chosen `UploadError` and the next cycle runs
- **THEN** the engine answers `Retry`, the job is re-created, and the queue's creation count for that key rises

#### Scenario: Job-limit truncates creation but not the cycle

- **WHEN** the job-limit is set below the number of `Work` resources in a cycle
- **THEN** `createJob` returns `LIMIT_EXCEEDED`, the cycle returns `PROCESSING`, the un-enqueued
  resources hold `DISCOVERED` rows, and the cycle still
  published its device manifest

#### Scenario: The job-limit is the only bound on creation

- **WHEN** the world's upload cycle enqueues more admitted rows than the settable job-limit allows
- **THEN** it creates jobs until `createJob` returns `LIMIT_EXCEEDED` and stops that pass there, having read
  no capacity from the queue

#### Scenario: The fake queue holds no ledger store

- **WHEN** the world constructs its fake job queue
- **THEN** it is given the world's ledger as a `TransferRecord`, exactly as a device transport is
