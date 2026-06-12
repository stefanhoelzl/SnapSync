# sync ledger Specification

## Purpose

The engine's durable per-key upload memory: a backend storage seam (dumb row store), a
reader/writer capability split (single writer per platform, codified by construction), and
self-contained idempotent record operations. The ledger is what makes skipping provable, reports
absorbable (at-least-once), and full re-enumeration harmless. Authoritative design:
docs/design.md §2.2.

## Requirements

### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerBackend` interface with exactly two
operations: `get(key): LedgerEntry?` and `put(entry)` — a single-row upsert. Backends SHALL store
entries verbatim (no interpretation, no precedence logic, last write wins). A `LedgerEntry` SHALL
carry `key`, `state` (`REQUESTED` | `COMPLETED` | `FAILED`), `attempt`, and `version`.

#### Scenario: Put then get round-trips
- **WHEN** `put(entry)` is called and then `get(entry.key)`
- **THEN** the returned entry equals the one put, field for field

#### Scenario: Put overwrites unconditionally
- **WHEN** `put` is called twice for the same key with different states
- **THEN** `get` returns the second entry — the backend applies no precedence of its own

#### Scenario: Unknown key reads null
- **WHEN** `get` is called for a key never put
- **THEN** it returns null

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

### Requirement: Record operations
`LedgerWriter` SHALL provide `recordRequested`, `recordCompleted`, and `recordFailed`. Each SHALL
upsert a complete, self-contained entry for the key (state, attempt, version as supplied by the
caller) — no operation depends on a prior read, and each maps to a single backend `put`.

#### Scenario: Requested entry
- **WHEN** `recordRequested(key, attempt, version)` is called
- **THEN** `entry(key)` has state `REQUESTED` with that attempt and version

#### Scenario: Completed entry
- **WHEN** `recordCompleted(key, attempt, version)` is called
- **THEN** `entry(key)` has state `COMPLETED` with that attempt and version

#### Scenario: Failed entry
- **WHEN** `recordFailed(key, attempt, version)` is called
- **THEN** `entry(key)` has state `FAILED` with that attempt and version

#### Scenario: Recording is idempotent
- **WHEN** the same record operation is applied twice with identical arguments
- **THEN** `entry(key)` is identical to applying it once

### Requirement: SQLDelight backend
A SQLDelight-backed `LedgerBackend` SHALL be provided in `:domain:sync` commonMain with the schema
`key TEXT PRIMARY KEY, state TEXT NOT NULL, attempt INTEGER NOT NULL, version TEXT NOT NULL` — no
timestamps, no further indexes. `put` SHALL be a single SQL upsert statement. JVM/sqlite driver
wiring SHALL exist for tests; native (iOS) driver wiring is out of scope.

#### Scenario: Backend contract holds on SQLite
- **WHEN** the storage-seam scenarios above run against the SQLDelight backend on a JVM sqlite
  driver
- **THEN** they pass unchanged
