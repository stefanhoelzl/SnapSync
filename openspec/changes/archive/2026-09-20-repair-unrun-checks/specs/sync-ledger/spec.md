## ADDED Requirements

### Requirement: Migration verification is backed by a committed schema snapshot

The claim that a migrated schema and the created schema are identical SHALL be discharged by an executing
check, not asserted. Each SQLDelight database SHALL commit a **schema snapshot** — the generated `.db` the
SQLDelight plugin emits for a schema version — into its own source folder, and SHALL declare the output
directory that makes the generating task exist. The verification task, which already runs inside
`./gradlew build`, SHALL apply every migration later than a snapshot's version to that snapshot and
compare the result with the schema the `CREATE` statements produce, failing the build on any difference.

This is load-bearing because **without a snapshot the task succeeds having compared nothing**. It is
registered, it runs, and it reports success on a chain that does not produce the created schema — which is
the shape of a check that costs a build gate to say nothing. A migration adding an object the `CREATE`
statements do not carry SHALL fail this task.

A snapshot SHALL be seeded at the schema version current when it is committed, and its staleness is
**not** a defect: an older snapshot has more migrations applied before the comparison, so it verifies more
of the chain than a newer one. There SHALL therefore be no freshness gate on a snapshot, and failing to
regenerate one after adding a migration SHALL weaken nothing.

The check compares **schema only**. A data-only migration changes no schema and SHALL remain invisible to
it, which is why such a migration's effect is asserted by a test instead.

#### Scenario: A migration that drifts from the CREATE statements fails the build

- **WHEN** a migration adds a schema object that the `CREATE` statements do not carry
- **THEN** the verification task fails, reporting the difference between the migrated and created schemas

#### Scenario: The chain and the created schema agree

- **WHEN** every migration later than the committed snapshot's version is applied to that snapshot
- **THEN** the resulting schema is identical to the one the `CREATE` statements produce

#### Scenario: A stale snapshot still verifies

- **WHEN** a migration is added and the snapshot is not regenerated
- **THEN** the task applies the new migration to the older snapshot and still compares against the created
  schema, so the check is not weakened

#### Scenario: A data-only migration is not covered

- **WHEN** a migration rewrites rows and changes no schema
- **THEN** the verification task cannot distinguish a correct rewrite from a wrong one, and the migration's
  effect is asserted by a migration test instead

## MODIFIED Requirements

### Requirement: SQLDelight backend

A SQLDelight-backed `LedgerStore` SHALL be provided in `:adapter:generic:app` commonMain (SQLDelight
package `app.snapsync.engine.db`; moved from `:domain:engine` at migration step 4, whose module
died at step 10) with the schema
`key TEXT PRIMARY KEY, assetId TEXT NOT NULL, state TEXT NOT NULL, attempt INTEGER NOT NULL,
eventId TEXT NOT NULL DEFAULT '', absent INTEGER NOT NULL DEFAULT 0`
plus **two** indexes: one on `assetId` (backing `markAbsent`, `markPresent` and the `assetId`-grouped
aggregate) and one on `destinationPath` (backing the acknowledgement lookup that resolves a returned
upload job to its row — capability `ios-photokit-upload`, on a path the OS invokes with a deadline).
**Every index SHALL be created by both routes** — the `CREATE` statements and the migration chain — so a
device that upgraded into the current schema carries the same objects as one created fresh. An index
reachable only by one route is the defect this states, not a detail: `ALTER TABLE … ADD COLUMN` creates no
index, so a column added by migration and indexed only in the `CREATE` statements leaves every upgraded
device without it. The `absent`
column records that an asset has left the library; its `DEFAULT 0` SHALL be present in **both** the
migration and the CREATE statement, like `eventId`'s, and it is the correct resting value for a row
written before the column existed. Reads that answer *what does this device hold or share* SHALL
exclude marked rows; `get` SHALL NOT, so upload suppression survives a deletion. `state`
SHALL be a SQLDelight typed column (`AS LedgerState` via the built-in enum adapter); adapter wiring
SHALL be hidden in a single factory function so construction sites never see it. The schema carries
no timestamp column. The `eventId` column's `DEFAULT ''` SHALL be present in **both** the migration
and the CREATE statement (the SQLDelight migration-verify task proves the two schemas identical — see
"Migration verification is backed by a committed schema snapshot", which is what makes that true),
and SHALL NOT be removed while any shipped build may write a 4-column row (see "Event provenance
and the backfill sweep", staged revert). The record write SHALL be a single guarded SQL upsert statement
whose applied/not-applied answer is read inside that statement's own transaction, like `markTerminal`'s;
`resetTo` SHALL insert with a plain `INSERT` inside its delete-all transaction;
`aggregates()` SHALL be a single
SQL round-trip (an `assetId`-grouped query). Every `LedgerStore` implementation SHALL satisfy the
shared `LedgerStoreContract` (hosted in `:test:world` commonMain since step 10): the JVM/sqlite and
native (simulator) driver tests extend it from `:adapter:generic:app`'s test source sets, and
`:adapter:generic:fake`'s honest `InMemoryLedgerStore` — the store the world harness runs on — extends it
from `:test:world`'s own tests. Every other `LedgerStore` test double SHALL honour the record guard and
`markPresent` the same way, so no test passes against a store that does something the device does not. The
native (iOS) driver is wired by `:adapter:ios:ext-safe`'s
factory over the App-Group container.

#### Scenario: Backend contract holds on SQLite
- **WHEN** the storage-seam, aggregate, and change-signal scenarios run against the SQLDelight
  backend on a JVM sqlite driver
- **THEN** they pass unchanged

#### Scenario: Every backend satisfies one contract
- **WHEN** the shared `LedgerStoreContract` scenarios run
- **THEN** they pass unchanged against the SQLDelight store (JVM and native drivers) and against
  `:adapter:generic:fake`'s in-memory store

#### Scenario: A pre-provenance column-explicit insert still works
- **WHEN** a 4-column column-explicit `INSERT OR REPLACE INTO ledgerRow (key, assetId, state,
  attempt)` — the shape a staged-revert build's generated queries emit — executes against the
  current 5-column schema
- **THEN** the row lands with `eventId = ''` (the DEFAULT fills the omitted column) and reads back
  through `get` as a sentinel row

#### Scenario: An upgraded device carries every index a fresh one does
- **WHEN** a database is brought to the current schema by the migration chain rather than created
- **THEN** it carries both the `assetId` and the `destinationPath` index, so the acknowledgement lookup
  is indexed on an upgraded device exactly as on a fresh one

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

The migration that adds the `absent` column (`6.sqm`, v6 -> v7) SHALL likewise be **row-preserving**,
and here that matters more than usual: a surviving `COMPLETED` row is exactly what stops the next cycle
re-uploading an already-stored resource, so losing them would re-upload every member's whole in-window
library. `ALTER TABLE ... ADD COLUMN` is a catalog-only change, so no row is touched. Every migrated row
SHALL land with `absent` unset, which is correct by construction: a row recorded before the column
existed was not marked absent. The `DEFAULT 0` SHALL be present in **both** the migration and the CREATE
statement, so the migration-verify task finds the two schemas identical.

The migration that retires the `UPLOADED` state (`8.sqm`, v8 -> v9) SHALL be a **data-only** rewrite:
`UPDATE ledgerRow SET state = 'COMPLETED' WHERE state = 'UPLOADED'`, touching no schema and no other
column. An `UPLOADED` row recorded that the bytes are stored, which is exactly what `COMPLETED` now records,
so the rewrite loses nothing. It is required rather than optional: `state` decodes through an enum that no
longer names `UPLOADED`, and the state-scoped reads compare the stored text against bound state sets, so an
unrewritten row would either fail to decode or count as pending forever with no error. The migration SHALL
NOT place anything in an event album and SHALL NOT create upload work: a converted row is simply settled.
Because it changes no schema, the migration-verify task cannot detect a wrong rewrite, so its effect SHALL be
asserted by a test that migrates a database holding an `UPLOADED` row.

The migration that creates the **`destinationPath` index** (`9.sqm`, v9 -> v10) SHALL repair an index that
the `CREATE` statements carried and the chain did not: the migration adding the `destinationPath` column
was catalog-only and created no index, so every device that upgraded through it lacks one. It SHALL be a
single `CREATE INDEX` over the existing table, **row-preserving** like its predecessors — it builds a
b-tree over the rows already present, rewrites none of them, settles nothing, and SHALL create **zero**
upload work. The index is not cosmetic: the acknowledgement path resolves every returned upload job
through it, in a process the OS invokes with a deadline, so its absence is a full table scan per returned
job on exactly the devices that have been running longest.

That statement SHALL be `CREATE INDEX **IF NOT EXISTS**`, because the omission left the v9 population in
two shapes and this migration runs on both: a database migrated through the column-adding migration has no
index, while one **created fresh** at any version since that column existed was built from the `CREATE`
statements and already has one. A bare `CREATE INDEX` SHALL NOT be used — it fails on the second shape,
which is a crash on update for every recent install. The repair SHALL therefore be idempotent, and
SHALL NOT drop and recreate an existing index to achieve that: SQLite normalizes `IF NOT EXISTS` out of
the stored schema text, so both routes already produce byte-identical schemas.

A migration repairing an object that only one route created SHALL be assumed to meet both shapes of the
population, in general and not only here — the divergence that made the repair necessary is the same
divergence that guarantees the repair meets databases that do not need it.

**Downgrade stance (recorded as contract):** a revert of the `UPLOADED` retirement SHALL be **staged** —
keep `8.sqm`, revert only the Kotlin — because the native driver refuses a database newer than the binary's
compiled schema. The reverted build understands `COMPLETED`. Re-applying the retirement after such a revert
SHALL ship a further migration carrying the same rewrite, because `8.sqm` has already run on every device
the revert reached. Decision record: `changes/archive/2026-09-15-retire-uploaded-state` (D3). The same
stance applies to the index migration, and is trivial there: it has no Kotlin counterpart to revert, so a
revert keeps `9.sqm` and changes nothing above it.

A fresh install SHALL create the current schema (no
timestamp column, `eventId` present with its DEFAULT, both indexes) directly.

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
  subsequent record carrying a real `eventId` for a new key round-trips

#### Scenario: Fresh database is created at the current schema
- **WHEN** a database is created from scratch
- **THEN** it has the `assetId` index and the `destinationPath` index, no `updatedAt` column, an
  `eventId` column defaulting to `''`, and needs no migration step

#### Scenario: Adding absent preserves the rows unmarked
- **WHEN** a v6 database holding a `COMPLETED` row is migrated to the current schema
- **THEN** the row survives with its `key`, `assetId`, `state`, `eventId` and manifest detail intact and
  its `absent` unset, so it still suppresses re-upload and still projects into the manifest

#### Scenario: An UPLOADED row is settled by the migration
- **WHEN** a v8 database holding a row whose stored state is `UPLOADED` is migrated to the current schema
- **THEN** that row reads back `COMPLETED` with every other column intact, and `aggregates()` counts its
  asset completed

#### Scenario: The rewrite touches nothing else
- **WHEN** a v8 database holding `DISCOVERED`, `REQUESTED`, `COMPLETED` and `FAILED` rows is migrated
- **THEN** every one of those rows keeps its state

#### Scenario: The index migration preserves every row and creates no work
- **WHEN** a v9 database that lacks the `destinationPath` index — the shape produced by upgrading through
  the column-adding migration — and holds `DISCOVERED`, `REQUESTED` and `COMPLETED` rows is migrated to
  the current schema
- **THEN** the `destinationPath` index exists, every row keeps its key, state and every other column, and
  no row becomes upload work

#### Scenario: The index migration does not fail on a database that already has the index
- **WHEN** a v9 database **created fresh** from the `CREATE` statements — which already carry the
  `destinationPath` index — is migrated to the current schema
- **THEN** the migration completes without error, the index is still present exactly once, and every row
  is untouched
