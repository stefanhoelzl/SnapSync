## MODIFIED Requirements

### Requirement: The UPLOADED state and its promotion

`LedgerState` SHALL carry a fourth value, `UPLOADED`: **the resource's bytes are durably stored, and the
work that a completion triggers has not yet run.** It is written by whichever party the platform tells that
the upload terminated, at the moment it is told; it is promoted to `COMPLETED` by the upload cycle once that
work has run.

`UPLOADED` SHALL be a **non-done** state (see "The done-state set is decided in Kotlin"): it counts toward
the backlog in every read. It SHALL, however, appear in the device-manifest projection like every other
state — the manifest declares what this device intends to provide, and a resource whose bytes are already
stored is intended by any reading (capability `device-manifest`). Only the cycle's promotion pass treats it
as outstanding work rather than as pending upload.

The engine's per-key decision SHALL treat `UPLOADED` as **already uploaded** (skip), like `COMPLETED` and
`REQUESTED`: its bytes are stored, so re-uploading them would be waste.

Adding this value SHALL require **no schema migration**: `state` is stored as text mapped to the enum, so a
database written by an earlier build simply contains no rows in the new state.

#### Scenario: A terminal upload is recorded before any cycle runs

- **WHEN** the platform reports that an upload for a `REQUESTED` key succeeded
- **THEN** that row's state becomes `UPLOADED`, and it remains `UPLOADED` across process death until a cycle
  promotes it

#### Scenario: An UPLOADED row is not re-uploaded

- **WHEN** discovery re-derives a resource whose row is `UPLOADED`
- **THEN** the engine answers already-uploaded and creates no upload job

#### Scenario: An UPLOADED row counts as outstanding but is still declared

- **WHEN** an asset has one `UPLOADED` row and no other rows
- **THEN** `aggregates()` counts that asset as pending, the pending-resource read returns its key, and the
  device-manifest projection **includes** it

#### Scenario: An older database needs no migration

- **WHEN** a build carrying `UPLOADED` opens a ledger written by a build that predates it
- **THEN** the schema is unchanged, every existing row decodes, and no migration step runs

### Requirement: The DISCOVERED state and the ledger as the upload work source

`LedgerState` SHALL carry a `DISCOVERED` value meaning **the discovery walk found this resource, the
membership's policy admitted it, and no upload has been attempted for it**. It SHALL be recorded for
every resource a cycle's walk admitted and the engine judged to be new work, **before** any upload
job is created for that cycle.

The ledger SHALL be the upload cycle's **source of work**: a producer SHALL enqueue from the ledger's
rows rather than from the walk's return value, so a cycle can make progress on work it already knows
about whatever the change feed reports. (A cycle still consults that feed — there is no cheaper way to
learn what the library did — but it no longer depends on the feed re-deriving work it has already
seen.) The `LedgerStore` SHALL expose a bounded state-scoped read of the rows that need a job, and
`DISCOVERED` and `FAILED` rows SHALL both be returned by it — they are the same fact to a producer,
differing only in whether an attempt has already been made.

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

### Requirement: The ledger row carries the manifest's presentation detail

Each ledger row SHALL carry, in addition to its dedup key and upload state, the fields the device manifest
requires to name a resource: the asset's `creationDate`, and per resource its `role`, `contentType`, and
human `filename`. These fields make the ledger the single durable, deletion-aware record of the device's
in-event resources, so the device manifest can be projected from it (capability `device-manifest`) rather
than maintained in a parallel accumulator that duplicated the same asset set. The dedup key and the
event-provenance `eventId` are unchanged.

A row SHALL carry this detail from the moment it is first recorded, not only once its upload completes: the
manifest declares intent, so a `DISCOVERED` row must already be able to name its resource. The discovery
walk supplies every field, so no additional platform read is required.

The `LedgerStore` read that serves the projection SHALL NOT be state-scoped, and its name SHALL NOT claim a
state. It SHALL return every row that is not marked absent, leaving admission to the membership's policy.

#### Scenario: A row names its resource fully as soon as it is recorded

- **WHEN** the discovery walk admits a resource and a `DISCOVERED` row is recorded for it
- **THEN** the row carries `creationDate`, `role`, `contentType`, and `filename` sufficient to build the
  resource's device-manifest entry with no additional PhotoKit read

#### Scenario: The manifest read is not state-scoped

- **WHEN** the projection reads the rows it lists
- **THEN** the read returns rows in every state, excluding only those marked absent
