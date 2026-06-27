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
`aggregates(): LedgerAggregates`, a change signal `changes: Flow<Unit>`, `clear()` — a
delete-all reset, `resetTo(entries)` — an **atomic** delete-all-then-insert-all replacement, and two
asset-targeted bulk removals: `deleteByAssetId(assetId)` — delete every row whose `assetId` equals
the argument — and `retainAssets(keep)` — delete every row whose `assetId` is not in the `keep` set.
Backends SHALL store entries verbatim (no interpretation, no precedence logic, last write wins, no
clocks of their own). A `LedgerEntry` SHALL carry `key`, `assetId`, `state` (`REQUESTED` |
`COMPLETED` | `FAILED`), `attempt`, and `updatedAt: Instant`. `clear()`, `resetTo`,
`deleteByAssetId`, and `retainAssets` SHALL each remove (and, for `resetTo`, then insert) the matching
rows and signal `changes` **once** like a `put` (so watchers re-read the now-current truth).
`clear()`, `resetTo`, `deleteByAssetId`, and `retainAssets` are **reset/bulk** operations, not the
per-key **record** operations; recording per-upload facts remains the single-record-writer's job
(`LedgerWriter`), so a non-writer holder of the backend may reset the store without breaching the
single-record-writer invariant. `assetId` is a second opaque field: the backend stores, groups, and
matches it by equality but never interprets it (it does not know what an "asset" means — any value is
valid, set by the caller), so the ledger remains a dumb, platform-neutral row store.

#### Scenario: Put then get round-trips
- **WHEN** `put(entry)` is called and then `get(entry.key)`
- **THEN** the returned entry equals the one put, field for field — including `assetId` and `updatedAt`

#### Scenario: Put overwrites unconditionally
- **WHEN** `put` is called twice for the same key with different states
- **THEN** `get` returns the second entry — the backend applies no precedence of its own

#### Scenario: Unknown key reads null
- **WHEN** `get` is called for a key never put
- **THEN** it returns null

#### Scenario: Clear empties the store and signals
- **WHEN** `clear()` is called on a store holding rows
- **THEN** every subsequent `get` returns null, `aggregates()` reports zero pending and completed,
  and a `changes` signal is emitted

#### Scenario: Reset replaces all rows and signals once
- **WHEN** `resetTo(entries)` is called on a store holding different rows
- **THEN** every prior key not in `entries` returns null, every key in `entries` returns its supplied
  entry verbatim, and exactly one `changes` signal is emitted

#### Scenario: Delete by assetId removes only that asset's rows and signals
- **WHEN** the store holds rows for assetId `A` (keys `A-photo.jpg`, `A-video.mov`) and assetId `B`
  (key `B-photo.jpg`), and `deleteByAssetId("A")` is called
- **THEN** `get("A-photo.jpg")` and `get("A-video.mov")` return null, `get("B-photo.jpg")` is
  unchanged, and a `changes` signal is emitted

#### Scenario: Retain assets removes the complement and signals
- **WHEN** the store holds rows for assetIds `A`, `B`, and `C`, and `retainAssets({"A", "C"})` is called
- **THEN** the `B` rows return null, the `A` and `C` rows are unchanged, and a `changes` signal is emitted

#### Scenario: Retain with empty set empties the store
- **WHEN** `retainAssets(emptySet)` is called on a store holding rows
- **THEN** every subsequent `get` returns null and a `changes` signal is emitted

### Requirement: Aggregate reads
`LedgerBackend.aggregates()` SHALL answer `LedgerAggregates(pending, completed,
newestCompletionAt: Instant?)` computed in one snapshot-consistent read, grouped by `assetId`
(a photo): `completed` = count of assets whose rows are ALL `COMPLETED`, `pending` = count of
assets with at least one non-`COMPLETED` row, `newestCompletionAt` = the maximum `updatedAt` over
the rows of fully-`COMPLETED` assets, or null when no asset is fully completed. The counts are
PHOTOS (assets), not resource rows. `LedgerAggregates` SHALL have value equality.

#### Scenario: Empty ledger aggregates
- **WHEN** `aggregates()` is called on an empty store
- **THEN** it answers `pending = 0, completed = 0, newestCompletionAt = null`

#### Scenario: A photo counts complete only when all its resources are
- **WHEN** one asset has two rows, one `COMPLETED` and one `REQUESTED`
- **THEN** `aggregates()` answers `pending = 1, completed = 0`

#### Scenario: Photos count by asset, not by row
- **WHEN** asset `A` has two `COMPLETED` rows and asset `B` has one `COMPLETED` and one `FAILED` row
- **THEN** `aggregates()` answers `pending = 1, completed = 1` (A complete, B pending)

#### Scenario: Newest completion is the latest fully-completed photo
- **WHEN** two assets are fully `COMPLETED` with different latest `updatedAt` values, and a third
  asset has a non-`COMPLETED` row
- **THEN** `newestCompletionAt` is the later of the two completed assets' times, and the
  partially-done asset never contributes to it

### Requirement: Change signal

`LedgerBackend.changes` SHALL emit `Unit` after every successful `put`. A ding carries no payload
and promises nothing beyond "re-read the truth" — consumers MUST treat it as a level trigger
(conflation, duplicate dings, and signals missed while busy are all safe because every re-read
queries current state). Where the underlying store is written by another process, the backend SHALL
feed `changes` from a cross-process notification — but that cross-process notification is the
**writer process's** signal that its work is durable, and SHALL be posted **once per writer work
cycle**, not after every `put`: the iOS App-Group backend SHALL NOT post a Darwin notification on
each `put`; instead the writer process (the extension) SHALL post one Darwin notification
(a `CFNotificationCenter` darwin-notify name) after its `process()` cycle completes, and the app-process
backend SHALL merge an observer of that notification into its `changes` flow. The in-process `changes`
ding on every `put` is unchanged (it has no in-writer-process consumer). The seam itself does not
change. A missed cross-process notification is harmless (the app re-reads on its next trigger).

#### Scenario: Put dings

- **WHEN** a collector is active on `changes` and `put` completes
- **THEN** the collector receives an emission

#### Scenario: A writer cycle dings the other process once

- **WHEN** the extension process performs several `put`s within one `process()` cycle and a collector
  in the app process is active on `changes`
- **THEN** the app-process collector receives one emission (via the single end-of-cycle Darwin
  notification) and re-reads current truth, rather than one emission per `put`

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
whose `snapshot: Flow<LedgerSnapshot>` is a cold flow that emits the current snapshot on collection
and re-queries on every backend ding, with equal consecutive values deduplicated. A `LedgerSnapshot`
SHALL carry `completed` and `newestCompletionAt` (the same scalars as `LedgerAggregates`, reused) and
`pendingByAsset: Map<assetId, Set<key>>` (the backlog grouped by photo), all read **point-in-time
consistently** within one ding so the scalars and the backlog never disagree. Each collection starts
with current truth — collectors share nothing. The watcher is the only ledger type that surfaces the
snapshot or dings; `LedgerReader` stays per-key (`entry(key)` only).

#### Scenario: Collection starts with current truth

- **WHEN** `snapshot` is collected over a store holding one `COMPLETED` key
- **THEN** the first emission reports `completed = 1` and an empty `pendingByAsset`, without any write
  occurring

#### Scenario: A write re-emits a consistent snapshot

- **WHEN** a `REQUESTED` key for a new asset is recorded while `snapshot` is collected
- **THEN** a new `LedgerSnapshot` is emitted whose `pendingByAsset` contains that asset's key and
  whose `completed` is unchanged, both from the same read

#### Scenario: Unchanged snapshot stays silent

- **WHEN** a write does not change the snapshot values (e.g. a `REQUESTED` key re-recorded with a new
  attempt that leaves the backlog and counts identical)
- **THEN** no new emission is observed

### Requirement: Record operations
`LedgerWriter` SHALL provide `recordRequested`, `recordCompleted`, and `recordFailed`. Each SHALL
upsert a complete, self-contained entry for the key (assetId, state, attempt as supplied
by the caller) — no operation depends on a prior read, and each maps to a single backend `put`.
`assetId` is supplied positionally as `recordX(key, assetId, attempt)` (the writer stays
on primitives, decoupled from the engine's `Resource`). The writer SHALL stamp `updatedAt` on
every record operation from an injected `Clock` (default: the system clock) — the writer is the
single stamping point; engine and backends stay clock-free. Duplicate record operations with
identical arguments SHALL converge on assetId, state, and attempt; the timestamp moves
forward with each application.

#### Scenario: Requested entry
- **WHEN** `recordRequested(key, assetId, attempt)` is called
- **THEN** `entry(key)` has state `REQUESTED` with that assetId and attempt

#### Scenario: Completed entry
- **WHEN** `recordCompleted(key, assetId, attempt)` is called
- **THEN** `entry(key)` has state `COMPLETED` with that assetId and attempt

#### Scenario: Failed entry
- **WHEN** `recordFailed(key, assetId, attempt)` is called
- **THEN** `entry(key)` has state `FAILED` with that assetId and attempt

#### Scenario: Record operations stamp the time
- **WHEN** a record operation runs with a fixed injected clock
- **THEN** `entry(key).updatedAt` equals the clock's instant

#### Scenario: Recording converges
- **WHEN** the same record operation is applied twice with identical arguments
- **THEN** `entry(key)` has the same assetId, state, and attempt as after one application
  — only `updatedAt` may differ

### Requirement: SQLDelight backend
A SQLDelight-backed `LedgerBackend` SHALL be provided in `:domain:engine` commonMain (SQLDelight
package `app.snapsync.engine.db`) with the schema
`key TEXT PRIMARY KEY, assetId TEXT NOT NULL, state TEXT NOT NULL, attempt INTEGER NOT NULL,
updatedAt INTEGER NOT NULL` (epoch milliseconds) plus an index on `assetId`
(backing `deleteByAssetId` and the `assetId`-grouped aggregate). `state` and `updatedAt` SHALL be
SQLDelight typed columns (`AS LedgerState` via the built-in enum adapter, `AS Instant` via an
epoch-millis adapter); adapter wiring SHALL be hidden in a single factory function so construction
sites never see it. `put` SHALL be a single SQL upsert statement; `aggregates()` SHALL be a single
SQL round-trip (an `assetId`-grouped query). JVM/sqlite driver wiring exists for tests; the native
(iOS) driver is wired by the `:app:ios` composition root over the App-Group container.

#### Scenario: Backend contract holds on SQLite
- **WHEN** the storage-seam, aggregate, and change-signal scenarios run against the SQLDelight
  backend on a JVM sqlite driver
- **THEN** they pass unchanged

### Requirement: Ledger schema migration
The SQLDelight schema SHALL be versioned and ship migrations that bring an existing on-device
`ledger.db` (in the App-Group container, which survives app reinstall) to the current schema. The
migration that drops the `version` column SHALL be **row-preserving** — existing rows, including
`COMPLETED` ones, SHALL survive it, because an uploaded resource is immutable and a surviving
`COMPLETED` row is exactly what keeps the next discovery cycle from re-uploading it. A fresh install
SHALL create the current (versionless) schema directly. (Dropping a column requires the SQLite
3.35+ grammar, so the SQLDelight dialect floor is raised accordingly — a build detail, not part of
the on-device contract.)

#### Scenario: Existing ledger drops the version column but keeps its rows
- **WHEN** a database created under the pre-immutable schema (holding `COMPLETED` rows with a
  `version` column) is opened under the current schema version
- **THEN** the migration runs without error, `ledgerRow` no longer has a `version` column, and the
  pre-existing rows (their `key`, `assetId`, `state`, `attempt`, `updatedAt`) are preserved

#### Scenario: Fresh database is created at the current schema
- **WHEN** a database is created from scratch
- **THEN** it has no `version` column and requires no migration step

### Requirement: Prune operations are writer-only
The two asset-keyed bulk removals (`deleteByAssetId`, `retainAssets`) SHALL be exposed on
`LedgerWriter` (delegating to the backend), and SHALL NOT be reachable through `LedgerReader`.
They are sync writes by the single ledger writer, not the app-side `clear()` reset, and at the
writer layer they neither stamp `updatedAt` nor consult engine state first (a backend may read its
own rows to compute a complement — an implementation detail, not part of the seam contract).
Granting read-only access by handing out the writer typed as `LedgerReader` SHALL therefore deny
prune access at compile time, preserving the single-writer invariant.

#### Scenario: Writer prunes by assetId
- **WHEN** a `LedgerWriter` records a row for assetId `X` (key `X-photo.jpg`) and then calls
  `deleteByAssetId("X")`
- **THEN** `entry("X-photo.jpg")` returns null

#### Scenario: Writer retains an asset set
- **WHEN** a `LedgerWriter` holds rows for assetIds `X` and `Y` and calls `retainAssets({"X"})`
- **THEN** the `Y` rows return null and the `X` rows are unchanged

#### Scenario: Reader-typed access cannot prune
- **WHEN** a component receives the ledger typed as `LedgerReader`
- **THEN** neither `deleteByAssetId` nor `retainAssets` is available to it at compile time

### Requirement: Prune operations hold on the SQLDelight backend
The SQLDelight-backed `LedgerBackend` SHALL implement `deleteByAssetId` and `retainAssets`.
`deleteByAssetId` SHALL be an indexed `DELETE … WHERE assetId = ?`. `retainAssets` SHALL delete
the complement of the supplied set without relying on an unbounded SQL `IN`/`NOT IN` parameter
list (so a multi-thousand-asset library does not exceed the driver's bind-variable limit) — e.g.
read the present assetIds, diff against `keep` in Kotlin, and delete each straggler. The
storage-seam scenarios for both operations SHALL pass against the SQLDelight backend on the JVM
sqlite driver via the shared backend contract.

#### Scenario: Backend prune contract holds on SQLite
- **WHEN** the delete-by-assetId and retain-assets storage-seam scenarios run against the
  SQLDelight backend on a JVM sqlite driver
- **THEN** they pass unchanged

#### Scenario: Retain assets over a large library stays within bind limits
- **WHEN** `retainAssets` is called on the SQLDelight backend with a keep-set larger than the
  driver's single-statement bind-variable limit
- **THEN** the complement is deleted with no bind-variable error

### Requirement: Pending-resource read

`LedgerBackend` SHALL expose a read of the non-`COMPLETED` rows as `(assetId, key)` pairs (the
backlog), so a status projection can group outstanding resources by photo without materializing the
whole table. The read SHALL return exactly the rows whose `state` is not `COMPLETED` and SHALL
interpret nothing else (the backend remains a dumb row store). On the SQLDelight backend it SHALL be
a single query (`SELECT assetId, key FROM ledgerRow WHERE state != 'COMPLETED'`).

#### Scenario: Returns only outstanding rows

- **WHEN** asset `A` has two `COMPLETED` rows and asset `B` has one `REQUESTED` and one `FAILED` row,
  and the pending-resource read is called
- **THEN** it returns only `B`'s two rows (`B`'s `REQUESTED` and `FAILED` keys), each paired with
  assetId `B`, and none of `A`'s

#### Scenario: Empty when nothing is outstanding

- **WHEN** every row is `COMPLETED`
- **THEN** the pending-resource read returns no rows

### Requirement: Atomic baseline reset

`LedgerBackend.resetTo(entries)` SHALL replace the entire store with `entries` in a single atomic
transaction: either all prior rows are removed and all `entries` inserted, or — on failure or
interruption — the store is left unchanged (no partial replacement is ever observable). It SHALL emit
exactly one `changes` signal on success. Entries are stored verbatim (the caller supplies `state`
and `updatedAt`); `resetTo` performs no clock stamping of its own. On the SQLDelight
backend it SHALL execute as one transaction.

#### Scenario: Interrupted reset leaves the store unchanged
- **WHEN** a `resetTo` transaction fails partway (e.g. an insert errors)
- **THEN** the store retains exactly its pre-call rows and no `changes` signal claims a new baseline

#### Scenario: Reset to a non-empty baseline is observable as a whole
- **WHEN** `resetTo(entries)` succeeds over a previously empty store
- **THEN** `aggregates()` reflects all `entries` at once and `get` returns each supplied entry verbatim

#### Scenario: Reset baseline holds on the SQLDelight backend
- **WHEN** the reset scenarios run against the SQLDelight backend on a JVM sqlite driver
- **THEN** they pass unchanged (a single-transaction replacement, one change signal)

