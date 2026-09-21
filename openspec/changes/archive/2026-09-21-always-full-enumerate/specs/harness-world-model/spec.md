## ADDED Requirements

### Requirement: Full-enumeration discovery driven by the in-memory gallery

The world SHALL provide a fake `UploadDiscovery` whose `discover(policy)` is a **full enumeration** of the
in-memory gallery, read through the honest `InMemoryCandidateSource`. That source answers the single
`CandidateSource.candidates(policy)` read over the world-owned asset cell, and its candidates map their
resources through the real shared fan-out when the cycle asks for them. Every readable discovery SHALL
report `Discovery.fullEnumeration = true`. There is no change feed and no token: an added asset appears in
the next discovery's candidates, and a removed asset is simply absent from it, which is exactly the evidence
the cycle's presence diff consumes (capability `sync-ledger`, "Deletion is a presence diff over an
authoritative walk").

The walk SHALL be narrowed the way the device's fetch is narrowed, and no further: by the rules a platform
predicate can express, returning a superset of the policy's capture window. It SHALL NOT be narrowed by the
full admission. On a device the fetch predicate cannot express the id-set exclusions or the resolution
floors, so an asset they exclude is still **present** in the walk. A fake that applied the whole admission
would make such an asset absent, and the cycle would delete its rows: in the harness alone, a narrowing
would become a deletion. That is the same false-deletion shape the retired reconcile shipped on device.

The same fake SHALL resolve ledger keys to uploadable resources (capability `ios-url-session-upload`) from the
world's in-memory gallery, and both reads SHALL be observable, so a test can assert **which keys** a cycle
resolved and **how many** discoveries it made. A key whose asset the operator has removed from the gallery
SHALL resolve to nothing.

The world SHALL provide an operator **unreadable-walk** lever that makes the next discovery answer as a
device does for an unreadable library: no candidates, and `fullEnumeration = false`. It is the case the
authoritative gate exists for, and without a lever no test could show that an unreadable walk deletes
nothing.

#### Scenario: Adding an asset yields a new candidate

- **WHEN** an asset is added to the in-memory gallery and discovery runs
- **THEN** `Discovery.candidates` carries that asset, and its resources fan out when the cycle asks

#### Scenario: Removing an asset deletes its in-window rows

- **WHEN** an asset whose in-window rows are `COMPLETED` is removed from the gallery and a cycle runs
- **THEN** the discovery no longer carries it, and the cycle deletes its rows, so it stops counting and
  stops being listed

#### Scenario: Narrowing the policy is not a removal

- **WHEN** the membership's capture-date cutoff is raised so assets still present in the gallery fall
  outside it, and a cycle then runs
- **THEN** those assets' ledger rows are kept: they are outside the walk's window, so the world shows the
  narrowing exactly as a device does, as rows the enqueue admission declines to upload rather than rows the
  walk retracted

#### Scenario: An asset the admission excludes is still present

- **WHEN** an asset with a `COMPLETED` row is added to a denylisted album while it stays in the gallery, and a
  cycle runs
- **THEN** the discovery still carries it and its rows are kept

#### Scenario: An unreadable walk deletes nothing

- **WHEN** the operator makes the walk unreadable, removes an asset with in-window rows, and a cycle runs
- **THEN** the discovery carries no candidates and reports no full enumeration, and no row is deleted

#### Scenario: A later cycle enqueues the remainder from the ledger

- **WHEN** a job-limited cycle is followed by another cycle with no gallery change in between
- **THEN** the remainder is enqueued by resolving its `DISCOVERED` rows' keys, and the second cycle's walk
  reads no already-recorded asset's resources

#### Scenario: A removed asset resolves to nothing

- **WHEN** the operator removes an asset from the gallery and a cycle resolves a ledger key for it
- **THEN** the resolution returns nothing for that key

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
  resources hold `DISCOVERED` rows, and the cycle still
  published its device manifest

#### Scenario: The fake queue reports neither live nor lost transfers

- **WHEN** the world's upload cycle reaches its stranded pass
- **THEN** the fake queue reports no live set and no lost set, so no row is recorded `FAILED` by that pass

#### Scenario: The fake queue holds no ledger store

- **WHEN** the world constructs its fake job queue
- **THEN** it is given the world's ledger as a `TransferRecord`, exactly as a device transport is

### Requirement: The world can model an unreadable membership

The world SHALL be able to present its membership as **unreadable**, distinctly from absent, so the skip
outcome (capability `upload-lifecycle`) is reachable from tests over the world.

This is the state a real device reaches on a background wake before first unlock, and it is the state three
shipped bugs have turned on. A world whose membership is a nullable cell can express only joined or absent,
so the outcome that matters most is the one no test can reach — the harness models the states that work and
omits the state that breaks.

#### Scenario: An unreadable membership is distinct from an absent one
- **WHEN** the world's membership is set unreadable and a cycle runs
- **THEN** the cycle skips, the joined-event marker is intact, and the ledger and object store are
  untouched

#### Scenario: An absent membership still drives the leave path
- **WHEN** the world's membership is cleared and a cycle runs
- **THEN** the leave-side reconciliation runs and the joined-event marker is cleared

## REMOVED Requirements

### Requirement: Token-delta discovery feed driven by the in-memory gallery
**Reason**: There is no change token any more: every walk is a full enumeration, and deletion is a presence diff over it rather than a removal signal.

**Migration**: Replaced by "Full-enumeration discovery driven by the in-memory gallery" (this change). The expire-token lever is replaced by an unreadable-walk lever.
