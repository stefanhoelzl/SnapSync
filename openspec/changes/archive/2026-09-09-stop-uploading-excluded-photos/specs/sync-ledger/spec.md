## MODIFIED Requirements

### Requirement: The DISCOVERED state and the ledger as the upload work source

`LedgerState` SHALL carry a `DISCOVERED` value meaning **the discovery walk found this resource, the
membership's policy admitted it, and no upload has been attempted for it**. It SHALL be recorded for
every resource a cycle's walk admitted and the engine judged to be new work, **before** any upload
job is created for that cycle.

The ledger SHALL be the upload cycle's **source of work**: a producer SHALL enqueue from the ledger's
rows rather than from the walk's return value, so a cycle can make progress on work it already knows
about whatever the change feed reports. (A cycle still consults that feed — there is no cheaper way to
learn what the library did — but it no longer depends on the feed re-deriving work it has already
seen.) The `LedgerStore` SHALL expose a state-scoped read of the rows that need a job, and
`DISCOVERED` and `FAILED` rows SHALL both be returned by it — they are the same fact to a producer,
differing only in whether an attempt has already been made.

A row needing a job records that the policy admitted its asset **when the row was written**, which is not
the same fact as the membership's *current* admission (`photo-selection-policy`). The cycle SHALL
therefore admit the rows this read returns before resolving or enqueuing any of them, and any bound on
how much work one cycle takes SHALL be applied to the **admitted** rows — bounding what a cycle
**resolves**, never what it reads. A bound applied to the read instead can starve: rows are returned in a
stable key order, so excluded rows sorting ahead of admitted ones would fill the batch on every cycle and
admitted work further down would never be reached. What the bound exists to protect is the platform
round-trip and, on the app-driven tier, the staged temp file — both of which follow the admitted rows.

`DISCOVERED` SHALL NOT be a done state, so a row in it counts toward the backlog everywhere. It SHALL
nonetheless be **included** in the device-manifest projection: the manifest declares what this device
intends to provide, and a resource the walk found and the policy admitted is precisely that (capability
`device-manifest`). It SHALL NOT be a stranding candidate: the stranded reconciliation reads `REQUESTED`
keys only, and surfacing a row that never had a job as a lost transfer would record a failure that did not
happen.

#### Scenario: A discovered resource is recorded before any job exists

- **WHEN** a cycle's walk admits a resource the engine judges to be new work
- **THEN** a `DISCOVERED` row is recorded for that resource's key before `createJob` is called for it

#### Scenario: A top-up enqueues from the ledger, not from the walk's output

- **WHEN** a cycle runs with rows in `DISCOVERED` or `FAILED` and its change feed reports nothing new
- **THEN** it resolves those rows' keys and enqueues them, rather than treating an empty change set as
  no work

#### Scenario: A FAILED row is re-enqueued without a full enumeration

- **WHEN** a row rests `FAILED` and its asset has not changed since the persisted discovery cursor
- **THEN** the next cycle re-enqueues it from the ledger, rather than waiting for a full enumeration
  to re-derive it

#### Scenario: A discovered row is backlog AND manifest

- **WHEN** a row is `DISCOVERED`
- **THEN** it counts toward the pending aggregate and the pending-resource read, and it appears in the
  device-manifest projection for every membership whose policy admits its asset

#### Scenario: A discovered row is never stranded

- **WHEN** the stranded reconciliation runs while a `DISCOVERED` row exists with no live transfer
- **THEN** that row is not surfaced as a lost transfer and is not written to `FAILED`

#### Scenario: The batch bounds the resolved work, not the read

- **WHEN** the rows needing a job exceed one cycle's batch and some of them are excluded by the
  membership's current policy
- **THEN** the cycle resolves at most one batch of **admitted** rows, and the excluded ones neither
  consume the batch nor prevent admitted rows from being enqueued

#### Scenario: An excluded row is retained, not pruned

- **WHEN** the membership's policy stops admitting an asset whose row needs a job
- **THEN** the row is left in the ledger untouched — no state change, no prune — so widening the policy
  again re-admits it and the next cycle enqueues it with no re-enumeration
