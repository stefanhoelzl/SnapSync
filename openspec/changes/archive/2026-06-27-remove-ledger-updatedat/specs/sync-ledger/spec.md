## MODIFIED Requirements

### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerBackend` interface with the row
operations `get(key): LedgerEntry?` and `put(entry)` (a single-row upsert), the aggregate read
`aggregates(): LedgerAggregates`, a change signal `changes: Flow<Unit>`, `clear()` — a
delete-all reset, `resetTo(entries)` — an **atomic** delete-all-then-insert-all replacement, and two
asset-targeted bulk removals: `deleteByAssetId(assetId)` — delete every row whose `assetId` equals
the argument — and `retainAssets(keep)` — delete every row whose `assetId` is not in the `keep` set.
Backends SHALL store entries verbatim (no interpretation, no precedence logic, last write wins, no
clocks of their own). A `LedgerEntry` SHALL carry `key`, `assetId`, `state` (`REQUESTED` |
`COMPLETED` | `FAILED`), and `attempt`. `clear()`, `resetTo`,
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
- **THEN** the returned entry equals the one put, field for field — including `assetId`

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
`LedgerBackend.aggregates()` SHALL answer `LedgerAggregates(pending, completed)` computed in one
snapshot-consistent read, grouped by `assetId` (a photo): `completed` = count of assets whose rows
are ALL `COMPLETED`, `pending` = count of assets with at least one non-`COMPLETED` row. The counts
are PHOTOS (assets), not resource rows. The aggregate carries no timestamp. `LedgerAggregates` SHALL
have value equality.

#### Scenario: Empty ledger aggregates
- **WHEN** `aggregates()` is called on an empty store
- **THEN** it answers `pending = 0, completed = 0`

#### Scenario: A photo counts complete only when all its resources are
- **WHEN** one asset has two rows, one `COMPLETED` and one `REQUESTED`
- **THEN** `aggregates()` answers `pending = 1, completed = 0`

#### Scenario: Photos count by asset, not by row
- **WHEN** asset `A` has two `COMPLETED` rows and asset `B` has one `COMPLETED` and one `FAILED` row
- **THEN** `aggregates()` answers `pending = 1, completed = 1` (A complete, B pending)

### Requirement: Ledger watcher

The ledger SHALL expose a third user-facing type alongside reader and writer: `LedgerWatcher`,
whose `snapshot: Flow<LedgerSnapshot>` is a cold flow that emits the current snapshot on collection
and re-queries on every backend ding, with equal consecutive values deduplicated. A `LedgerSnapshot`
SHALL carry `completed` (the same scalar as `LedgerAggregates`, reused) and
`pendingByAsset: Map<assetId, Set<key>>` (the backlog grouped by photo), both read **point-in-time
consistently** within one ding so the scalar and the backlog never disagree. Each collection starts
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
on primitives, decoupled from the engine's `Resource`). The writer records no timestamp and reads
no clock — the engine, writer, and backends are all clock-free. Duplicate record operations with
identical arguments SHALL converge on assetId, state, and attempt.

#### Scenario: Requested entry
- **WHEN** `recordRequested(key, assetId, attempt)` is called
- **THEN** `entry(key)` has state `REQUESTED` with that assetId and attempt

#### Scenario: Completed entry
- **WHEN** `recordCompleted(key, assetId, attempt)` is called
- **THEN** `entry(key)` has state `COMPLETED` with that assetId and attempt

#### Scenario: Failed entry
- **WHEN** `recordFailed(key, assetId, attempt)` is called
- **THEN** `entry(key)` has state `FAILED` with that assetId and attempt

#### Scenario: Recording converges
- **WHEN** the same record operation is applied twice with identical arguments
- **THEN** `entry(key)` has the same assetId, state, and attempt as after one application

### Requirement: SQLDelight backend
A SQLDelight-backed `LedgerBackend` SHALL be provided in `:domain:engine` commonMain (SQLDelight
package `app.snapsync.engine.db`) with the schema
`key TEXT PRIMARY KEY, assetId TEXT NOT NULL, state TEXT NOT NULL, attempt INTEGER NOT NULL`
plus an index on `assetId` (backing `deleteByAssetId` and the `assetId`-grouped aggregate). `state`
SHALL be a SQLDelight typed column (`AS LedgerState` via the built-in enum adapter); adapter wiring
SHALL be hidden in a single factory function so construction sites never see it. The schema carries
no timestamp column. `put` SHALL be a single SQL upsert statement; `aggregates()` SHALL be a single
SQL round-trip (an `assetId`-grouped query). JVM/sqlite driver wiring exists for tests; the native
(iOS) driver is wired by the `:app:ios` composition root over the App-Group container.

#### Scenario: Backend contract holds on SQLite
- **WHEN** the storage-seam, aggregate, and change-signal scenarios run against the SQLDelight
  backend on a JVM sqlite driver
- **THEN** they pass unchanged

### Requirement: Ledger schema migration
The SQLDelight schema SHALL be versioned and ship migrations that bring an existing on-device
`ledger.db` (in the App-Group container, which survives app reinstall) to the current schema. The
migration that drops the `updatedAt` column SHALL be **row-preserving** — existing rows, including
`COMPLETED` ones, SHALL survive it (the dropped column is neither the primary key nor indexed), so an
app update keeps the ledger's recorded state and forces no re-enumeration or re-reconcile. It is a
single `ALTER TABLE ledgerRow DROP COLUMN updatedAt`; the SQLite 3.35+ grammar this requires is
already satisfied by the dialect floor raised for the preceding column-drop migration (a build
detail, not part of the on-device contract). A fresh install SHALL create the current schema (no
timestamp column) directly.

#### Scenario: Dropping updatedAt preserves the rows
- **WHEN** a database holding `ledgerRow` records with an `updatedAt` column is opened under the
  schema version that removes it
- **THEN** the `ALTER TABLE … DROP COLUMN updatedAt` migration runs without error, every row's
  `key`, `assetId`, `state`, and `attempt` are preserved, and `ledgerRow` no longer has an
  `updatedAt` column

#### Scenario: Fresh database is created at the current schema
- **WHEN** a database is created from scratch
- **THEN** it has the `assetId` index, no `updatedAt` column, and needs no migration step

### Requirement: Atomic baseline reset

`LedgerBackend.resetTo(entries)` SHALL replace the entire store with `entries` in a single atomic
transaction: either all prior rows are removed and all `entries` inserted, or — on failure or
interruption — the store is left unchanged (no partial replacement is ever observable). It SHALL emit
exactly one `changes` signal on success. Entries are stored verbatim (the caller supplies `state`);
`resetTo` performs no clock stamping of its own. On the SQLDelight backend it SHALL execute as one
transaction.

#### Scenario: Interrupted reset leaves the store unchanged
- **WHEN** a `resetTo` transaction fails partway (e.g. an insert errors)
- **THEN** the store retains exactly its pre-call rows and no `changes` signal claims a new baseline

#### Scenario: Reset to a non-empty baseline is observable as a whole
- **WHEN** `resetTo(entries)` succeeds over a previously empty store
- **THEN** `aggregates()` reflects all `entries` at once and `get` returns each supplied entry verbatim

#### Scenario: Reset baseline holds on the SQLDelight backend
- **WHEN** the reset scenarios run against the SQLDelight backend on a JVM sqlite driver
- **THEN** they pass unchanged (a single-transaction replacement, one change signal)
