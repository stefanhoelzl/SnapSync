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
action SHALL transfer the job's bytes with a real `PUT` to the device-facing upload route of the world's
backend ("The world's backend is one seam with two implementations") — the route and addressing the real
uploaders use — and move the job to the acknowledge bucket, so the next cycle records it `COMPLETED`. The
action suspends until the backend has answered. The double SHALL NOT deposit into any backend
store-direct, so a completed object is one the chosen backend itself accepted. An operator **fail** action SHALL move the job to the retry bucket carrying a chosen engine
`UploadError` (`Network`, `Http`, `Cancelled`, or `Unknown`), driving the real engine retry chain: the
engine answers `Retry` with a freshly minted request and the job is re-created. The engine carries no attempt
count (capability `sync-engine`), so the queue records its own per-key count of the creations it observed. The
queue's pending/retry/acknowledge buckets and per-key creation count SHALL be
inspectable so tests assert the lifecycle, not only the final outcome.

#### Scenario: Complete deposits the object and the ledger records COMPLETED

- **WHEN** the operator completes a created job and the next upload cycle runs
- **THEN** the object key is present in the backend's per-device listing, read through the backend-neutral
  inspection, and the ledger holds a `COMPLETED` row for it

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

#### Scenario: Complete over the real backend

- **WHEN** the world is built over the real backend and the operator completes a created job
- **THEN** the real backend received the bytes on its upload route, and its per-device listing names the
  object

## ADDED Requirements

### Requirement: The world's backend is one seam with two implementations

The world SHALL reach its backend through **one** seam with two implementations:
- the **mini-edge**: the in-memory backend store served by the `MockEngine` mini-edge. This is the default,
  and it is available on every target the world declares.
- the **real backend**: the Deno `api/` served as a local, loopback-only process with an ephemeral
  filesystem store. It is JVM-only, and started through the test-only module that owns that process.

Everything the world does to its backend SHALL go through the seam:
- the client its real HTTP seams share;
- the upload double's byte transfer;
- the operator's seeding (events, joins, manifests, foreign devices' bytes);
- the backend-neutral inspection and levers.

The real-backend implementation SHALL use the backend's public HTTP surface only. It SHALL NOT read the
process's storage directory, which would make tests depend on the backend's storage layout.

Every neutral read and lever SHALL answer either a value or **unavailable on this backend**, with a reason.
It SHALL NOT answer an empty value or silently do nothing for an operation the backend cannot honour. On
the real backend these are unavailable:
- the manifest read (the backend serves no route that reads a manifest back);
- the publish counters and the stored manifest version;
- backend-offline;
- the minimum-app-version lever;
- the event sweep, the byte wipe and the byte collection.

#### Scenario: The default world

- **WHEN** a world is built with no backend argument
- **THEN** it is over the mini-edge, and every existing test's behaviour is unchanged

#### Scenario: A lever the backend cannot honour

- **WHEN** a test pulls backend-offline on a world built over the real backend
- **THEN** the lever answers unavailable on this backend with its reason, and the backend's behaviour is
  unchanged

#### Scenario: Bytes and joins reach the real backend

- **WHEN** a world over the real backend provisions an event, completes an upload job and injects a foreign
  device
- **THEN** the real backend's event-union, read through the neutral inspection, includes the foreign
  device's complete assets, and its per-device listing names the completed object

### Requirement: Neutral inspection and minted event ids beside the mini-edge-only surface

The world SHALL offer, **beside** its existing surface:
- a backend-neutral inspection and lever API;
- provision and foreign-device helpers that return the event id **the backend minted**. The mini-edge mints
  UUIDs for these too, as the real backend does.

The existing public backend store and the helpers that take a caller-chosen event id SHALL remain, and
SHALL be documented as **mini-edge-only**. Reading the store on a world over the real backend SHALL fail
with a stated error rather than answer an empty store.

#### Scenario: A caller-chosen id on the real backend

- **WHEN** a test provisions a caller-chosen event id on a world over the real backend
- **THEN** it fails with a stated error naming the minted-id helper, because the real backend mints event
  ids and refuses others

#### Scenario: The existing surface on the mini-edge

- **WHEN** an existing test reads the backend store or provisions a caller-chosen id on the default world
- **THEN** it behaves exactly as before this change
