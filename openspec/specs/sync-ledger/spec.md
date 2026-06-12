# sync ledger Specification

## Purpose

The engine's durable per-key upload memory: a backend storage seam (dumb row store that signals
its own changes), a three-way capability split — reader (per-key, engine-facing), writer
(records, single per platform, codified by construction), watcher (aggregate stream,
status-facing) — and self-contained idempotent record operations. The ledger is what makes
skipping provable, reports absorbable (at-least-once), full re-enumeration harmless, and status
a read-only projection. Authoritative design: docs/design.md §2.2.

## Requirements

### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerBackend` interface with the row
operations `get(key): LedgerEntry?` and `put(entry)` (a single-row upsert), the aggregate read
`aggregates(): LedgerAggregates`, and a change signal `changes: Flow<Unit>`. Backends SHALL store
entries verbatim (no interpretation, no precedence logic, last write wins, no clocks of their
own). A `LedgerEntry` SHALL carry `key`, `state` (`REQUESTED` | `COMPLETED` | `FAILED`),
`attempt`, `version`, and `updatedAt: Instant`.

#### Scenario: Put then get round-trips
- **WHEN** `put(entry)` is called and then `get(entry.key)`
- **THEN** the returned entry equals the one put, field for field — including `updatedAt`

#### Scenario: Put overwrites unconditionally
- **WHEN** `put` is called twice for the same key with different states
- **THEN** `get` returns the second entry — the backend applies no precedence of its own

#### Scenario: Unknown key reads null
- **WHEN** `get` is called for a key never put
- **THEN** it returns null

### Requirement: Aggregate reads
`LedgerBackend.aggregates()` SHALL answer `LedgerAggregates(pending, completed,
newestCompletionAt: Instant?)` computed in one snapshot-consistent read: `pending` = count of
keys whose state is not `COMPLETED`, `completed` = count of `COMPLETED` keys,
`newestCompletionAt` = the maximum `updatedAt` over `COMPLETED` rows, or null when none exist.
`LedgerAggregates` SHALL have value equality.

#### Scenario: Empty ledger aggregates
- **WHEN** `aggregates()` is called on an empty store
- **THEN** it answers `pending = 0, completed = 0, newestCompletionAt = null`

#### Scenario: Mixed states count by proof
- **WHEN** the store holds one `REQUESTED`, one `FAILED`, and two `COMPLETED` keys
- **THEN** `aggregates()` answers `pending = 2, completed = 2`

#### Scenario: Newest completion wins
- **WHEN** two keys are `COMPLETED` with different `updatedAt` values
- **THEN** `newestCompletionAt` is the later of the two, and non-`COMPLETED` rows never
  contribute to it

### Requirement: Change signal
`LedgerBackend.changes` SHALL emit `Unit` after every successful `put`. A ding carries no payload
and promises nothing beyond "re-read the truth" — consumers MUST treat it as a level trigger
(conflation, duplicate dings, and signals missed while busy are all safe because every re-read
queries current state). Where the underlying store is written by another process, feeding
`changes` from the platform's cross-process notification is that backend's implementation
concern — the seam does not change.

#### Scenario: Put dings
- **WHEN** a collector is active on `changes` and `put` completes
- **THEN** the collector receives an emission

### Requirement: Reader and writer capability split
The ledger SHALL expose a concrete shared `LedgerReader` (query: `entry(key): LedgerEntry?`) and a
concrete shared `LedgerWriter` that subclasses `LedgerReader` (record operations). Record and
query semantics SHALL be implemented once in these shared classes, delegating storage to the
injected `LedgerBackend` — so a `LedgerWriter` is usable wherever a `LedgerReader` is expected,
and read-only access is granted by handing out the writer typed as `LedgerReader`.

#### Scenario: Writer reads what it wrote
- **WHEN** a `LedgerWriter` records an entry and `entry(key)` is called on the same instance
- **THEN** the recorded entry is returned

#### Scenario: Reader-typed access cannot record
- **WHEN** a component receives the ledger typed as `LedgerReader`
- **THEN** no record operation is available to it at compile time

### Requirement: Ledger watcher
The ledger SHALL expose a third user-facing type alongside reader and writer: `LedgerWatcher`,
whose `aggregates: Flow<LedgerAggregates>` is a cold flow that emits the current aggregates on
collection and re-queries on every backend ding, with equal consecutive values deduplicated.
Each collection starts with current truth — collectors share nothing. The watcher is the only
ledger type that surfaces aggregates or dings; `LedgerReader` stays per-key
(`entry(key)` only).

#### Scenario: Collection starts with current truth
- **WHEN** `aggregates` is collected over a store holding one `COMPLETED` key
- **THEN** the first emission reports `completed = 1` without any write occurring

#### Scenario: A write re-emits
- **WHEN** a key is recorded while `aggregates` is collected
- **THEN** a new `LedgerAggregates` reflecting the write is emitted

#### Scenario: Unchanged aggregates stay silent
- **WHEN** a write does not change the aggregate values (e.g. a `REQUESTED` key re-recorded with
  a new attempt)
- **THEN** no new emission is observed

### Requirement: Record operations
`LedgerWriter` SHALL provide `recordRequested`, `recordCompleted`, and `recordFailed`. Each SHALL
upsert a complete, self-contained entry for the key (state, attempt, version as supplied by the
caller) — no operation depends on a prior read, and each maps to a single backend `put`. The
writer SHALL stamp `updatedAt` on every record operation from an injected `Clock` (default: the
system clock) — the writer is the single stamping point; engine and backends stay clock-free.
Duplicate record operations with identical arguments SHALL converge on state, attempt, and
version; the timestamp moves forward with each application.

#### Scenario: Requested entry
- **WHEN** `recordRequested(key, attempt, version)` is called
- **THEN** `entry(key)` has state `REQUESTED` with that attempt and version

#### Scenario: Completed entry
- **WHEN** `recordCompleted(key, attempt, version)` is called
- **THEN** `entry(key)` has state `COMPLETED` with that attempt and version

#### Scenario: Failed entry
- **WHEN** `recordFailed(key, attempt, version)` is called
- **THEN** `entry(key)` has state `FAILED` with that attempt and version

#### Scenario: Record operations stamp the time
- **WHEN** a record operation runs with a fixed injected clock
- **THEN** `entry(key).updatedAt` equals the clock's instant

#### Scenario: Recording converges
- **WHEN** the same record operation is applied twice with identical arguments
- **THEN** `entry(key)` has the same state, attempt, and version as after one application —
  only `updatedAt` may differ

### Requirement: SQLDelight backend
A SQLDelight-backed `LedgerBackend` SHALL be provided in `:domain:engine` commonMain (SQLDelight
package `app.snapsync.engine.db`) with the schema
`key TEXT PRIMARY KEY, state TEXT NOT NULL, attempt INTEGER NOT NULL, version TEXT NOT NULL,
updatedAt INTEGER NOT NULL` (epoch milliseconds) — no further indexes. `state` and `updatedAt`
SHALL be SQLDelight typed columns (`AS LedgerState` via the built-in enum adapter,
`AS Instant` via an epoch-millis adapter); adapter wiring SHALL be hidden in a single factory
function so construction sites never see it. `put` SHALL be a single SQL upsert statement;
`aggregates()` SHALL be a single SQL round-trip. JVM/sqlite driver wiring SHALL exist for tests;
native (iOS) driver wiring is out of scope.

#### Scenario: Backend contract holds on SQLite
- **WHEN** the storage-seam, aggregate, and change-signal scenarios run against the SQLDelight
  backend on a JVM sqlite driver
- **THEN** they pass unchanged
