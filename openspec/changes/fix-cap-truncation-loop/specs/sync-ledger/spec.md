## ADDED Requirements

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

`DISCOVERED` SHALL NOT be a done state, so a row in it counts toward the backlog everywhere and is
excluded from the device-manifest projection. It SHALL NOT be a stranding candidate: the stranded
reconciliation reads `REQUESTED` keys only, and surfacing a row that never had a job as a lost
transfer would record a failure that did not happen.

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

#### Scenario: A discovered row is backlog, not manifest

- **WHEN** a row is `DISCOVERED`
- **THEN** it counts toward the pending aggregate and the pending-resource read, and it appears in no
  device-manifest projection

#### Scenario: A discovered row is never stranded

- **WHEN** the stranded reconciliation runs while a `DISCOVERED` row exists with no live transfer
- **THEN** that row is not surfaced as a lost transfer and is not written to `FAILED`

### Requirement: The needs-job set is decided in Kotlin

Which `LedgerState` values **need an upload job** SHALL be decided by a single exhaustive `when` in
`:domain` `model/`, and bound into the work-source read as a parameter — never written as a literal
inside a query. It is a second, independent classification alongside the done-state set: a state may
be neither done nor in need of a job (`REQUESTED`, `UPLOADED`), and every state SHALL be classified on
both axes.

A state added without classifying it on **both** axes SHALL fail to compile, rather than landing
silently on one side of either.

#### Scenario: A new state must be classified on both axes

- **WHEN** a value is added to `LedgerState` and either the done-state decision or the needs-job
  decision is not updated
- **THEN** the build fails, because each decision is an exhaustive `when` with no `else` branch

#### Scenario: The two classifications are independent

- **WHEN** the classifications are applied to `REQUESTED` and `UPLOADED`
- **THEN** neither is done and neither needs a job, so a read of one set never implies the other

## MODIFIED Requirements

### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerStore` interface with the row
operations `get(key): LedgerEntry?` and `put(entry)` (a single-row upsert), the guarded terminal write
`markTerminal(key, state): Boolean` (see "Guarded terminal write"), the guarded promotion
`promoteUploaded(key): Boolean` (see "Guarded promotion"), the state-scoped read of `UPLOADED` rows
(see "Uploaded-row read"), the state-scoped read of `REQUESTED` keys, the bounded state-scoped read of
rows that **need a job** (see "The DISCOVERED state and the ledger as the upload work source"), the
manifest projection read and its detail backfill, the aggregate read
`aggregates(): LedgerAggregates`, a change signal `changes: Flow<Unit>`, `clear()` — a
delete-all reset, `clearRequested()` — delete every `REQUESTED` row, `resetTo(entries)` — an **atomic**
delete-all-then-insert-all replacement, the asset-targeted bulk mark `markAbsent(assetId)` — mark
every row whose `assetId` equals the argument as absent, **keeping** the rows — and the provenance
sweep `backfillEventId(eventId)` (see "Event provenance and the backfill sweep").
Backends SHALL store entries verbatim (no interpretation, no precedence logic, last write wins, no
clocks of their own). A `LedgerEntry` SHALL carry `key`, `assetId`, `state` (`DISCOVERED` |
`REQUESTED` | `UPLOADED` | `COMPLETED` | `FAILED`), `attempt`, and `eventId` — the event that was
joined when the row was recorded. `clear()`, `clearRequested()`, `resetTo`, `markAbsent`, an applied
`markTerminal` and an applied `promoteUploaded` SHALL each remove (and, for `resetTo`, then insert) or
update the matching rows and signal `changes` **once** like a `put` (so watchers re-read the
now-current truth).
`clear()`, `clearRequested()`, `resetTo`, and `markAbsent` are **reset/bulk** operations, not the
per-key **record** operations; recording per-upload facts remains the single record-writer's job, so a
non-writer holder of the backend may reset the store without breaching the
single-record-writer invariant. `markTerminal` is a **record** operation and is exposed here deliberately —
see "Reader and writer capability split" for why that does not breach the invariant. `assetId` is a second
opaque field: the backend stores, groups, and
matches it by equality but never interprets it (it does not know what an "asset" means — any value is
valid, set by the caller), so the ledger remains a dumb, platform-neutral row store. `eventId` is a
third opaque field with the same posture: the backend stores it verbatim and matches it by equality
only where an operation's contract says so (the backfill's sentinel match); it does not know what an
"event" means.

There is deliberately **no** `deleteByAssetId` and **no** `retainAssets`. Both were removed when
retention stopped being driven by the selection policy (see "The ledger is never pruned by the
selection policy"): a departed asset's rows are **marked**, never deleted, because their bytes are
still on the backend and the rows are what stop a restored asset re-uploading.

#### Scenario: Put then get round-trips
- **WHEN** `put(entry)` is called and then `get(entry.key)`
- **THEN** the returned entry equals the one put, field for field — including `assetId` and `eventId`

#### Scenario: A guarded terminal write signals like a put
- **WHEN** `markTerminal` applies to a row
- **THEN** `changes` signals exactly once, as it would for a `put`

#### Scenario: There is no delete-by-asset
- **WHEN** an asset leaves the device's library
- **THEN** its rows are marked absent and retained, and no seam operation exists that deletes rows by
  `assetId`

### Requirement: Record operations
`LedgerWriter` SHALL provide `recordDiscovered`, `recordRequested`, `recordCompleted`, and
`recordFailed`. Each SHALL
upsert a complete, self-contained entry for the key (assetId, state, attempt, eventId as supplied
by the caller) — no operation depends on a prior read, and each maps to a single backend `put`.
`assetId` and `eventId` are supplied positionally as `recordX(key, assetId, attempt, eventId)` (the
writer stays on primitives, decoupled from the engine's `Resource`; the eventId is per-call because
the writer outlives any one membership — it is constructed at composition time, while the joined
event arrives per cycle with the gate). `recordDiscovered` additionally carries the resource's
manifest detail, because the walk is the only reader of a capture date and a row recorded without one
is excluded from every projection until a later walk backfills it. The writer records no timestamp and reads
no clock — the engine, writer, and backends are all clock-free. Duplicate record operations with
identical arguments SHALL converge on assetId, state, attempt, and eventId.

`recordDiscovered` SHALL NOT overwrite a row that already exists in any other state: a resource that
is `REQUESTED`, `UPLOADED` or `COMPLETED` is not new work, and re-recording it would either duplicate
an in-flight job or discard a fact about the world.

#### Scenario: Discovered entry
- **WHEN** `recordDiscovered` is called for a resource whose key has no row
- **THEN** `entry(key)` has state `DISCOVERED` with that resource's assetId and the supplied eventId,
  and carries the manifest detail the resource was discovered with

#### Scenario: Discovering an already-recorded key changes nothing
- **WHEN** `recordDiscovered` is called for a key whose row is `REQUESTED`, `UPLOADED` or `COMPLETED`
- **THEN** the row is unchanged

#### Scenario: Requested entry
- **WHEN** `recordRequested(key, assetId, attempt, eventId)` is called
- **THEN** `entry(key)` has state `REQUESTED` with that assetId, attempt, and eventId

#### Scenario: Completed entry
- **WHEN** `recordCompleted(key, assetId, attempt, eventId)` is called
- **THEN** `entry(key)` has state `COMPLETED` with that assetId, attempt, and eventId

#### Scenario: Failed entry
- **WHEN** `recordFailed(key, assetId, attempt, eventId)` is called
- **THEN** `entry(key)` has state `FAILED` with that assetId, attempt, and eventId

#### Scenario: Recording converges
- **WHEN** the same record operation is applied twice with identical arguments
- **THEN** `entry(key)` has the same assetId, state, attempt, and eventId as after one application
