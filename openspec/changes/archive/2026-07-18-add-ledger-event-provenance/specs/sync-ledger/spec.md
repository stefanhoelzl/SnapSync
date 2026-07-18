# sync-ledger — delta for add-ledger-event-provenance

## MODIFIED Requirements

### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerStore` interface with the row
operations `get(key): LedgerEntry?` and `put(entry)` (a single-row upsert), the aggregate read
`aggregates(): LedgerAggregates`, a change signal `changes: Flow<Unit>`, `clear()` — a
delete-all reset, `resetTo(entries)` — an **atomic** delete-all-then-insert-all replacement, two
asset-targeted bulk removals: `deleteByAssetId(assetId)` — delete every row whose `assetId` equals
the argument — and `retainAssets(keep)` — delete every row whose `assetId` is not in the `keep` set —
and the provenance sweep `backfillEventId(eventId)` (see "Event provenance and the backfill sweep").
Backends SHALL store entries verbatim (no interpretation, no precedence logic, last write wins, no
clocks of their own). A `LedgerEntry` SHALL carry `key`, `assetId`, `state` (`REQUESTED` |
`COMPLETED` | `FAILED`), `attempt`, and `eventId` — the event that was joined when the row was
recorded. `clear()`, `resetTo`,
`deleteByAssetId`, and `retainAssets` SHALL each remove (and, for `resetTo`, then insert) the matching
rows and signal `changes` **once** like a `put` (so watchers re-read the now-current truth).
`clear()`, `resetTo`, `deleteByAssetId`, and `retainAssets` are **reset/bulk** operations, not the
per-key **record** operations; recording per-upload facts remains the single-record-writer's job
(`LedgerWriter`), so a non-writer holder of the backend may reset the store without breaching the
single-record-writer invariant. `assetId` is a second opaque field: the backend stores, groups, and
matches it by equality but never interprets it (it does not know what an "asset" means — any value is
valid, set by the caller), so the ledger remains a dumb, platform-neutral row store. `eventId` is a
third opaque field with the same posture: the backend stores it verbatim and matches it by equality
only where an operation's contract says so (the backfill's sentinel match); it does not know what an
"event" means.

#### Scenario: Put then get round-trips
- **WHEN** `put(entry)` is called and then `get(entry.key)`
- **THEN** the returned entry equals the one put, field for field — including `assetId` and `eventId`

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

#### Scenario: eventId is stored verbatim including the sentinel
- **WHEN** one entry is put with a real `eventId` and another with the pre-provenance sentinel `""`
- **THEN** `get` returns each `eventId` exactly as supplied — the backend neither fills nor
  interprets the sentinel on a row operation

### Requirement: Record operations
`LedgerWriter` SHALL provide `recordRequested`, `recordCompleted`, and `recordFailed`. Each SHALL
upsert a complete, self-contained entry for the key (assetId, state, attempt, eventId as supplied
by the caller) — no operation depends on a prior read, and each maps to a single backend `put`.
`assetId` and `eventId` are supplied positionally as `recordX(key, assetId, attempt, eventId)` (the
writer stays on primitives, decoupled from the engine's `Resource`; the eventId is per-call because
the writer outlives any one membership — it is constructed at composition time, while the joined
event arrives per cycle with the gate). The writer records no timestamp and reads
no clock — the engine, writer, and backends are all clock-free. Duplicate record operations with
identical arguments SHALL converge on assetId, state, attempt, and eventId.

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

### Requirement: SQLDelight backend

A SQLDelight-backed `LedgerStore` SHALL be provided in `:adapter:generic` commonMain (SQLDelight
package `app.snapsync.engine.db`; moved from `:domain:engine` at migration step 4, whose module
died at step 10) with the schema
`key TEXT PRIMARY KEY, assetId TEXT NOT NULL, state TEXT NOT NULL, attempt INTEGER NOT NULL,
eventId TEXT NOT NULL DEFAULT ''`
plus an index on `assetId` (backing `deleteByAssetId` and the `assetId`-grouped aggregate). `state`
SHALL be a SQLDelight typed column (`AS LedgerState` via the built-in enum adapter); adapter wiring
SHALL be hidden in a single factory function so construction sites never see it. The schema carries
no timestamp column. The `eventId` column's `DEFAULT ''` SHALL be present in **both** the migration
and the CREATE statement (the SQLDelight migration-verify task proves the two schemas identical),
and SHALL NOT be removed while any shipped build may write a 4-column row (see "Event provenance
and the backfill sweep", staged revert). `put` SHALL be a single SQL upsert statement;
`aggregates()` SHALL be a single
SQL round-trip (an `assetId`-grouped query). Every `LedgerStore` implementation SHALL satisfy the
shared `LedgerStoreContract` (hosted in `:test:world` commonMain since step 10): the JVM/sqlite and
native (simulator) driver tests extend it from `:adapter:generic`'s test source sets, and
`:adapter:fake`'s honest `InMemoryLedgerStore` — the store the world harness runs on — extends it
from `:test:world`'s own tests. The native (iOS) driver is wired by `:adapter:ios:ext-safe`'s
factory over the App-Group container.

#### Scenario: Backend contract holds on SQLite
- **WHEN** the storage-seam, aggregate, and change-signal scenarios run against the SQLDelight
  backend on a JVM sqlite driver
- **THEN** they pass unchanged

#### Scenario: Every backend satisfies one contract
- **WHEN** the shared `LedgerStoreContract` scenarios run
- **THEN** they pass unchanged against the SQLDelight store (JVM and native drivers) and against
  `:adapter:fake`'s in-memory store

#### Scenario: A pre-provenance column-explicit insert still works
- **WHEN** a 4-column column-explicit `INSERT OR REPLACE INTO ledgerRow (key, assetId, state,
  attempt)` — the shape a staged-revert build's generated queries emit — executes against the
  current 5-column schema
- **THEN** the row lands with `eventId = ''` (the DEFAULT fills the omitted column) and reads back
  through `get` as a sentinel row

### Requirement: Ledger schema migration
The SQLDelight schema SHALL be versioned and ship migrations that bring an existing on-device
`ledger.db` (in the App-Group container, which survives app reinstall) to the current schema. The
migration that drops the `updatedAt` column SHALL be **row-preserving** — existing rows, including
`COMPLETED` ones, SHALL survive it (the dropped column is neither the primary key nor indexed), so an
app update keeps the ledger's recorded state and forces no re-enumeration or re-reconcile. It is a
single `ALTER TABLE ledgerRow DROP COLUMN updatedAt`; the SQLite 3.35+ grammar this requires is
already satisfied by the dialect floor raised for the preceding column-drop migration (a build
detail, not part of the on-device contract).

The migration that adds the `eventId` column (`4.sqm`, v4 → v5) SHALL likewise be
**row-preserving**: a single catalog-only
`ALTER TABLE ledgerRow ADD COLUMN eventId TEXT NOT NULL DEFAULT ''`, after which every
pre-existing row — including every `COMPLETED` row — survives with all prior fields intact and
`eventId = ''` (the pre-provenance sentinel). The migration SHALL NOT attempt to fill the true
event id: that value lives in config, which migration SQL cannot reach; filling it is the
writer's backfill sweep (see "Event provenance and the backfill sweep"). The primary key SHALL
remain `key`. Because a surviving `COMPLETED` row is what stops re-upload, an update-in-place
over a joined install SHALL create **zero** new upload jobs from this migration alone.

A fresh install SHALL create the current schema (no
timestamp column, `eventId` present with its DEFAULT) directly.

#### Scenario: Dropping updatedAt preserves the rows
- **WHEN** a database holding `ledgerRow` records with an `updatedAt` column is opened under the
  schema version that removes it
- **THEN** the `ALTER TABLE … DROP COLUMN updatedAt` migration runs without error, every row's
  `key`, `assetId`, `state`, and `attempt` are preserved, and `ledgerRow` no longer has an
  `updatedAt` column

#### Scenario: Adding eventId preserves the rows and fills the sentinel
- **WHEN** a database holding v4 `ledgerRow` records (including `COMPLETED` ones) is opened under
  the schema version that adds `eventId`
- **THEN** the `ALTER TABLE … ADD COLUMN eventId` migration runs without error, every row's
  `key`, `assetId`, `state`, and `attempt` are preserved, every row reads `eventId = ''`, and a
  subsequent `put` carrying a real `eventId` round-trips

#### Scenario: Fresh database is created at the current schema
- **WHEN** a database is created from scratch
- **THEN** it has the `assetId` index, no `updatedAt` column, an `eventId` column defaulting to
  `''`, and needs no migration step

### Requirement: Event-independent key

The ledger key SHALL be the **bare resource filename** (`<assetId>-<role>.<ext>`), carrying no event
scoping. Because the key is event-independent, a `COMPLETED` row recorded while one event is
configured stays valid and continues to read as `COMPLETED` after the configured event changes — the
ledger neither keys nor **reads** by event: no dedup decision, aggregate, backlog read, or skip
consults `eventId`. Rows **record** the joined event as provenance (see "Event provenance and the
backfill sweep"), but that provenance is write-side annotation only — recording it changes no read
result. This is what lets cross-event dedup come purely from the reconcile seed source (a `resetTo`
clear-and-seed from the device-global per-device listing) without any ledger key change.

#### Scenario: A COMPLETED row stays valid after the configured event changes
- **WHEN** a resource is recorded `COMPLETED` under one event and the configured event later changes
- **THEN** `get`/`entry` for that bare filename still returns the `COMPLETED` row, unaffected by the event change

#### Scenario: The key carries no event scoping
- **WHEN** two configured events would reference the same resource
- **THEN** they resolve to the **same** ledger key (the bare filename), so a single `COMPLETED` row serves both

#### Scenario: Provenance changes no decision
- **WHEN** the engine adjudicates a resource whose ledger row carries any `eventId` — real or the
  `''` sentinel
- **THEN** the decision (skip on `COMPLETED`/`REQUESTED`, work on `FAILED`/absent) is identical to
  the decision for the same states under any other `eventId`

## ADDED Requirements

### Requirement: Event provenance and the backfill sweep

Every ledger row SHALL carry the `eventId` that was joined when the row was recorded, as
**provenance, not dedup state**: new record operations and reconciliation seeds write the live
event id; no read consults it (multi-event reads are future work). The empty string `''` SHALL be
the single **pre-provenance sentinel**, meaning "recorded by a build that did not carry
provenance" — the `4.sqm` migration default, or a staged-revert build's 4-column writes — and
SHALL never be supplied as a live event id by the engine or the reconciler.

`LedgerStore` SHALL provide `backfillEventId(eventId)`: rewrite `eventId` on **exactly** the rows
whose value is the sentinel, leaving every other field of every row — and every row already
carrying a real event id — untouched. The sweep SHALL be idempotent (a sweep matching no rows is a
no-op) and SHALL emit one `changes` signal like the other bulk operations (the signal is a level
trigger; uniformity here is what keeps a future event-scoped read from meeting an unsignaled
mutation). On the SQLDelight backend it SHALL be a single
`UPDATE ledgerRow SET eventId = ? WHERE eventId = ''`.

The sweep is **writer-family**: it SHALL be exposed on `LedgerWriter` (like the prunes) and SHALL
be executed by the shared upload cycle — the single-writer seat that runs on **both** tiers and
never in a reader — once per cycle, **after** the re-join reconciliation settles (a settled
reconcile means the marker agrees with the configured event, and a switch's authoritative
`resetTo` has already re-baselined, so the sweep can never label another event's rows). A cycle
whose gate skips, whose membership is definitively absent, or whose reconcile defers SHALL NOT
sweep. A sweep failure SHALL NOT fail the cycle (the sentinel is durable; the next settled cycle
retries).

**Downgrade stance (recorded as contract):** the v5 schema is a one-way door — the native driver
refuses to open a database whose on-disk version is newer than the binary's compiled schema
(SQLiter throws `Database version N newer than config version M`), so no v4-schema binary can
open a migrated store. A behavior revert of this capability SHALL therefore keep `4.sqm` and the
`eventId` column (reverting only the Kotlin surface); the column's `DEFAULT ''` is what keeps
such a build's 4-column inserts working, as sentinel rows the next post-re-update sweep labels.
Decision record: `changes/archive/add-ledger-event-provenance`, D4–D5.

#### Scenario: The sweep rewrites only the sentinel
- **WHEN** the store holds sentinel rows and a row recorded under another event, and
  `backfillEventId("E1")` is called
- **THEN** every sentinel row reads `eventId = "E1"` with its `key`, `assetId`, `state`, and
  `attempt` unchanged, and the other event's row is untouched

#### Scenario: The sweep is idempotent
- **WHEN** `backfillEventId("E1")` succeeds and a later `backfillEventId("E2")` runs
- **THEN** the second sweep finds no sentinel rows and changes nothing

#### Scenario: The writer's settled cycle sweeps and new records carry the live event
- **WHEN** an update-in-place leaves sentinel rows and the next upload cycle enters with a settled
  membership
- **THEN** the cycle sweeps the sentinel rows to the joined event id before creating work, and
  every row the cycle's engine records carries that event id

#### Scenario: An unsettled cycle does not sweep
- **WHEN** a cycle's reconcile defers (the device listing failed or timed out)
- **THEN** no sweep runs and the sentinel rows survive for the next settled cycle

#### Scenario: Reconciliation seeds are born with provenance
- **WHEN** a re-join reconciliation `resetTo`s the ledger from the per-device listing
- **THEN** every seeded `COMPLETED` row carries the reconciled event's id — no seeded row is a
  sentinel row
