## MODIFIED Requirements

### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerStore` interface with the row
read `get(key): LedgerEntry?`, the guarded record write `recordUnlessSettled(entry): Boolean` (see
"Record operations" — a single-row upsert that never overwrites a row in a done state, and answers whether
it applied), the guarded terminal write
`markTerminal(key, outcome): Boolean` (see "Guarded terminal write"), the state-scoped read of `REQUESTED` keys, the bounded state-scoped read of
rows that **need a job** (see "The DISCOVERED state and the ledger as the upload work source"), the
manifest projection read and its detail backfill, the aggregate read
`aggregates(): LedgerAggregates`, a change signal `changes: Flow<Unit>`, `clear()` — a
delete-all reset, `demoteRequested()` — return every `REQUESTED` row to `DISCOVERED` (see "Requested-state reset"), `resetTo(entries)` — an **atomic**
delete-all-then-insert-all replacement, the key-targeted delete `deleteKeys(keys)` — delete exactly the
rows whose `key` is among the arguments and no other — and the batch record write `recordAllUnlessSettled(entries)`
(see "A walk re-reads only the assets the ledger does not fully know").

There is deliberately **no** unconditional per-row upsert (`put`). It was removed when the record path became
guarded: with no production caller left, it could only serve as an unguarded door for the next production
write. Tests seed a store through the guarded record write or `resetTo`, exactly as production writes it.

There is deliberately **no** promotion and **no** uploaded-row read. A successful upload is recorded
`COMPLETED` at the moment the platform reports it, so no state exists between "the bytes are stored" and
"nothing further is owed".

There is deliberately **no** attempt count, **no** event provenance, and **no** absence mark on a row. None of
them decided anything: the engine retries forever with no budget, no read consulted the event a row was
recorded under, and a departed asset's rows are deleted rather than marked. The `10.sqm` migration dropped all
three (see "Ledger schema migration"). Decision record: `changes/shrink-the-ledger-row`.

Backends SHALL store the fields of an applied write verbatim (no interpretation, no clocks of their own). The
**only** precedence a backend applies is the one each named guarded operation states — the record write's
done-state guard and `markTerminal`'s `REQUESTED` guard — and each SHALL be
enforced inside the storage statement itself, never by a read followed by a write. The reset family SHALL
apply no precedence at all. A `LedgerEntry` SHALL carry `key`, `assetId`, and `state` (`DISCOVERED` |
`REQUESTED` | `COMPLETED`), plus the manifest detail and the destination path described below.
`clear()`, `demoteRequested()`, `resetTo`, an
applied `deleteKeys`, an
applied record write (a batch record write that applied to any row signals once for the whole batch), and an applied
`markTerminal` SHALL each remove (and, for `resetTo`, then insert) or
update the matching rows and signal `changes` **once** (so watchers re-read the
now-current truth).
`clear()`, `demoteRequested()`, `resetTo`, and `deleteKeys` are **reset/bulk** operations, not the
per-key **record** operations; recording per-upload facts remains the single record-writer's job, so a
non-writer holder of the backend may reset the store without breaching the
single-record-writer invariant. `markTerminal` is a **record** operation and is exposed here deliberately —
see "Reader and writer capability split" for why that does not breach the invariant. `assetId` is a second
opaque field: the backend stores, groups, and
matches it by equality but never interprets it (it does not know what an "asset" means — any value is
valid, set by the caller), so the ledger remains a dumb, platform-neutral row store.

There is deliberately **no** `deleteByAssetId` and **no** `retainAssets`. Both were removed when
retention stopped being driven by the selection policy (see "The ledger is never pruned by the
selection policy"), and neither returns with presence-driven deletion. Deletion is **key-scoped**: every
caller of `deleteKeys` names exactly the rows it holds evidence about — a key that failed to resolve, or a
row an authoritative walk did not return (see "Deletion is a presence diff over an authoritative walk") —
so no operation can reach the settled siblings of a row it did not judge. Several resources of one photo
share an `assetId` and hold per-key states, so an asset-scoped delete driven by a key-grained read would
reach rows the read never selected.

#### Scenario: A recorded entry round-trips
- **WHEN** `recordUnlessSettled(entry)` is called for a key with no row, and then `get(entry.key)`
- **THEN** the write reports applied, and the returned entry equals the one recorded, field for field —
  including `assetId`

#### Scenario: A guarded terminal write signals like a record
- **WHEN** `markTerminal` applies to a row
- **THEN** `changes` signals exactly once, as it would for an applied record write

#### Scenario: There is no unconditional upsert
- **WHEN** the `LedgerStore` interface is inspected
- **THEN** it declares no per-row write that replaces a row regardless of the row's state

#### Scenario: There is no bulk delete of requested rows
- **WHEN** the `LedgerStore` interface is inspected
- **THEN** it declares no operation that deletes rows by state; the only bulk operation over `REQUESTED` rows
  is `demoteRequested()`, which keeps them

#### Scenario: There is no delete-by-asset
- **WHEN** the `LedgerStore` interface is inspected
- **THEN** it declares no operation that deletes rows by `assetId`; `deleteKeys` deletes only the keys it is
  given

#### Scenario: Deleting one key leaves its asset's other rows
- **WHEN** assetId `X` has a `COMPLETED` row `X-primary.heic` and a `DISCOVERED` row `X-live.mov`, and
  `deleteKeys({"X-live.mov"})` is called
- **THEN** `get("X-live.mov")` returns nothing, `get("X-primary.heic")` still returns the `COMPLETED` row with
  every field unchanged, and `changes` signals once

#### Scenario: A row carries no attempt, provenance, or absence mark
- **WHEN** the `LedgerEntry` type and the `LedgerStore` interface are inspected
- **THEN** a row has no attempt count, no event id, and no absence mark, and the store declares no provenance
  sweep and no absence-mark sweep

### Requirement: Aggregate reads
`LedgerStore.aggregates()` SHALL answer `LedgerAggregates(pending, completed)` computed in one
snapshot-consistent read, grouped by `assetId` (a photo): `completed` = count of assets whose rows are ALL in
a **done** state, `pending` = count of assets with at least one **non-done** row (see "The done-state set is
decided in Kotlin"). The counts
are PHOTOS (assets), not resource rows. The aggregate carries no timestamp. `LedgerAggregates` SHALL
have value equality.

#### Scenario: Empty ledger aggregates
- **WHEN** `aggregates()` is called on an empty store
- **THEN** it answers `pending = 0, completed = 0`

#### Scenario: A photo counts complete only when all its resources are
- **WHEN** one asset has two rows, one `COMPLETED` and one `REQUESTED`
- **THEN** `aggregates()` answers `pending = 1, completed = 0`

#### Scenario: Photos count by asset, not by row
- **WHEN** asset `A` has two `COMPLETED` rows and asset `B` has one `COMPLETED` and one `DISCOVERED` row
- **THEN** `aggregates()` answers `pending = 1, completed = 1` (A complete, B pending)

#### Scenario: A photo counts complete as soon as its last upload is recorded
- **WHEN** an asset's only `REQUESTED` row is recorded `COMPLETED` through `markTerminal`, and no cycle has
  run since
- **THEN** `aggregates()` counts that asset completed

### Requirement: Record operations
`LedgerWriter` SHALL provide `recordDiscovered`, `recordRequested`, and
`recordFailed`. Each SHALL
upsert a complete, self-contained entry for the key (assetId and state)
through the backend's guarded record write, `recordUnlessSettled` — one storage
statement per record. `recordFailed` records **`DISCOVERED`**: a failed upload returns its row to the state
that needs a job (see "The DISCOVERED state and the ledger as the upload work source"). The operation keeps its
name because the name says why the call is made, while the state says what it records.
The writer stays on primitives, decoupled from the engine's `Resource`, and carries no per-membership
argument: a row records no event. `recordDiscovered` additionally carries the resource's
manifest detail, because the walk is the only reader of a capture date and a row recorded without one
is excluded from every projection until a later walk backfills it. `recordRequested` additionally carries the
destination path (see "The ledger records the destination a job was sent to"). The writer records no timestamp and reads
no clock — the engine, writer, and backends are all clock-free. Duplicate record operations with
identical arguments SHALL converge on assetId and state.

A record operation SHALL NOT overwrite a row whose current state is in the **done-state set** (see "The
done-state set is decided in Kotlin"). The guard SHALL be enforced **inside the storage statement** — on the
SQLDelight backend one `INSERT … ON CONFLICT(key) DO UPDATE … WHERE state NOT IN :doneStates`, with the set
bound as a parameter — and SHALL NOT depend on a read the writer made first. A read-then-write is not atomic
against a second writer, and a late record over a settled row would require a job for bytes the backend
already holds. Transitions between non-done states (a retry `DISCOVERED → REQUESTED`, a failed or stranded
transfer `REQUESTED → DISCOVERED`) SHALL still apply. A record the guard declined SHALL NOT be silent: the writer SHALL log
it, naming the key and the refused state.

There is **no** writer operation that records `COMPLETED`. A completed upload is a fact the platform reports,
recorded through the guarded `markTerminal` (see "Guarded terminal write"); a resource already known to be
stored is seeded `COMPLETED` by the re-join reconciliation's `resetTo`.

`recordDiscovered` SHALL NOT overwrite a row that already exists in any other state: a resource that
is `REQUESTED` or `COMPLETED` is not new work, and re-recording it would either duplicate
an in-flight job or discard a fact about the world. A key whose row is already `DISCOVERED` is left as it is:
the write would say only what the row already says.

#### Scenario: Discovered entry
- **WHEN** `recordDiscovered` is called for a resource whose key has no row
- **THEN** `entry(key)` has state `DISCOVERED` with that resource's assetId,
  and carries the manifest detail the resource was discovered with

#### Scenario: Discovering an already-recorded key changes nothing
- **WHEN** `recordDiscovered` is called for a key whose row is `REQUESTED` or `COMPLETED`
- **THEN** the row is unchanged

#### Scenario: Requested entry
- **WHEN** `recordRequested(key, assetId)` is called
- **THEN** `entry(key)` has state `REQUESTED` with that assetId

#### Scenario: Failed entry
- **WHEN** `recordFailed(key, assetId)` is called
- **THEN** `entry(key)` has state `DISCOVERED` with that assetId

#### Scenario: Recording converges
- **WHEN** the same record operation is applied twice with identical arguments
- **THEN** `entry(key)` has the same assetId and state as after one application

#### Scenario: A settled row survives every record operation
- **WHEN** `recordRequested` or `recordFailed` is called for a
  key whose row is `COMPLETED`
- **THEN** the row is unchanged, field for field, and the backend reports the write as not applied

#### Scenario: A retry still re-requests a failed row
- **WHEN** `recordFailed` has returned a key's row to `DISCOVERED` and `recordRequested` is then called for it
- **THEN** `entry(key)` has state `REQUESTED`

#### Scenario: A stranded transfer still returns a requested row to the work source
- **WHEN** `recordFailed` is called for a key whose row is `REQUESTED`
- **THEN** `entry(key)` has state `DISCOVERED`

#### Scenario: The reset family still replaces settled rows
- **WHEN** `resetTo` is called with entries for keys whose rows are `COMPLETED`
- **THEN** the store holds exactly the supplied entries, whatever the replaced rows' states were

#### Scenario: The writer cannot record a completion
- **WHEN** a component holds a `LedgerWriter`
- **THEN** it has no operation that records `COMPLETED` for a key

### Requirement: SQLDelight backend

A SQLDelight-backed `LedgerStore` SHALL be provided in `:adapter:generic:app` commonMain (SQLDelight
package `app.snapsync.engine.db`; moved from `:domain:engine` at migration step 4, whose module
died at step 10) with the schema
`key TEXT PRIMARY KEY, assetId TEXT NOT NULL, state TEXT NOT NULL`, the four manifest-detail columns
(`creationDate`, `role`, `contentType`, `originalFilename`, each `TEXT NOT NULL DEFAULT ''`), and the nullable
`destinationPath TEXT`,
plus an index on `assetId` (backing the `assetId`-grouped aggregate) and an index on `destinationPath`. The
schema carries **no** `attempt`, `eventId` or `absent` column: `10.sqm` dropped them (see "Ledger schema
migration"). `state`
SHALL be a SQLDelight typed column (`AS LedgerState` via the built-in enum adapter); adapter wiring
SHALL be hidden in a single factory function so construction sites never see it. The schema carries
no timestamp column. Each manifest-detail column's `DEFAULT ''` SHALL be present in **both** the migration
and the CREATE statement (the SQLDelight migration-verify task proves the two schemas identical). The record write SHALL be a single guarded SQL upsert statement
whose applied/not-applied answer is read inside that statement's own transaction, like `markTerminal`'s;
`resetTo` SHALL insert with a plain `INSERT` inside its delete-all transaction;
`recordAllUnlessSettled` SHALL apply its entries through the same guarded statement inside **one**
transaction; `deleteKeys` SHALL delete by primary key in chunks below every driver's bind-variable limit;
`aggregates()` SHALL be a single
SQL round-trip (an `assetId`-grouped query). Every `LedgerStore` implementation SHALL satisfy the
shared `LedgerStoreContract` (hosted in `:test:world` commonMain since step 10): the JVM/sqlite and
native (simulator) driver tests extend it from `:adapter:generic:app`'s test source sets, and
`:adapter:generic:fake`'s honest `InMemoryLedgerStore` — the store the world harness runs on — extends it
from `:test:world`'s own tests. Every other `LedgerStore` test double SHALL honour the record guard and
`deleteKeys` the same way, so no test passes against a store that does something the device does not. The
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

#### Scenario: The schema carries no retired column
- **WHEN** the columns of `ledgerRow` are listed on a database created fresh or migrated to the current schema
- **THEN** there is no `attempt`, `eventId` or `absent` column

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
`eventId = ''`. The primary key SHALL
remain `key`. Because a surviving `COMPLETED` row is what stops re-upload, an update-in-place
over a joined install SHALL create **zero** new upload jobs from this migration alone. The column it adds is
dropped again by `10.sqm`.

The migration that adds the `absent` column (`6.sqm`, v6 -> v7) SHALL likewise be **row-preserving**,
and here that matters more than usual: a surviving `COMPLETED` row is exactly what stops the next cycle
re-uploading an already-stored resource, so losing them would re-upload every member's whole in-window
library. `ALTER TABLE ... ADD COLUMN` is a catalog-only change, so no row is touched. Every migrated row
SHALL land with `absent` unset, which is correct by construction: a row recorded before the column
existed was not marked absent. The column it adds is dropped again by `10.sqm`.

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

The migration that **shrinks the row** (`10.sqm`, v10 -> v11) SHALL retire the `FAILED` state and the three
columns nothing reads, in this order and nothing else:

```
UPDATE ledgerRow SET state = 'DISCOVERED' WHERE state = 'FAILED';
ALTER TABLE ledgerRow DROP COLUMN attempt;
ALTER TABLE ledgerRow DROP COLUMN eventId;
ALTER TABLE ledgerRow DROP COLUMN absent;
```

The rewrite is **required**, for the reason `8.sqm`'s is: `state` decodes through an enum that no longer names
`FAILED`, and the state-scoped reads compare the stored text against bound state sets. A read alias that
decoded stored `'FAILED'` as `DISCOVERED` SHALL NOT be used instead: the work-source read selects `state IN
:needsJobStates` in SQL, so an aliased row would decode correctly and never be selected — pending forever,
with no job and no error. The rewrite loses nothing: a `FAILED` row and a `DISCOVERED` row were already the
same fact to a producer. It SHALL NOT create upload work beyond what the rows already owed, and SHALL NOT
settle any row.

The drops SHALL be **row-preserving** like every earlier column drop: every row, and every other column of it,
survives. Each statement SHALL be valid on **both** shapes of the v10 population — a database created fresh
from the `CREATE` statements and one upgraded through the chain. Both carry all three columns (`attempt`
since the first schema, `eventId` since `4.sqm`, `absent` since `6.sqm`), and none of them is indexed, part of
the primary key, `UNIQUE`, or named by a constraint, trigger or view, so SQLite accepts each drop on either
shape; no `IF EXISTS` form is needed. A row an earlier build left with the absence mark becomes reachable by
every read, which is what the retired per-cycle mark sweep did.

Because the migration-verify task compares schemas only, it verifies the drops and cannot see the rewrite.
The rewrite's effect SHALL be asserted by a test (`SqlDelightLedgerStoreTest`) that plants rows by raw insert
into a v10 database — including a `FAILED` row carrying manifest detail and a destination path, a row with the
absence mark set, and a row with an empty `eventId` — migrates it, and checks every row.

**Downgrade stance (recorded as contract):** a revert of the `UPLOADED` retirement SHALL be **staged** —
keep `8.sqm`, revert only the Kotlin — because the native driver refuses a database newer than the binary's
compiled schema. The reverted build understands `COMPLETED`. Re-applying the retirement after such a revert
SHALL ship a further migration carrying the same rewrite, because `8.sqm` has already run on every device
the revert reached. Decision record: `changes/archive/2026-09-15-retire-uploaded-state` (D3). The same
stance applies to the index migration, and is trivial there: it has no Kotlin counterpart to revert, so a
revert keeps `9.sqm` and changes nothing above it.

`10.sqm` **cannot** be undone by a staged revert. Keeping it while reverting the Kotlin fails: the reverted
`CREATE` statements name the dropped columns, so the migration-verify task rejects the build, and every query
selecting them would fail at runtime. Removing it instead leaves every upgraded device on a schema newer than
the binary, which the native driver refuses to open. A rollback of `10.sqm` SHALL therefore be a
**roll-forward**: a further migration re-adding `attempt INTEGER NOT NULL DEFAULT 0`, `eventId TEXT NOT NULL
DEFAULT ''` and `absent INTEGER NOT NULL DEFAULT 0`, shipped with the earlier Kotlin, whose `CREATE` statement
gains the same `DEFAULT 0` on `attempt` so the two schemas stay identical. `FAILED` need not be restored: the
earlier Kotlin treats a `DISCOVERED` row exactly as it treated a `FAILED` one. Decision record:
`changes/shrink-the-ledger-row`.

A fresh install SHALL create the current schema (no
timestamp, `attempt`, `eventId` or `absent` column; both indexes) directly.

#### Scenario: Dropping updatedAt preserves the rows
- **WHEN** a database holding `ledgerRow` records with an `updatedAt` column is opened under the
  current schema
- **THEN** every migration runs without error, every row's
  `key`, `assetId`, and `state` are preserved, and `ledgerRow` no longer has an
  `updatedAt` column

#### Scenario: Adding eventId preserves the rows
- **WHEN** a database holding v4 `ledgerRow` records (including `COMPLETED` ones) is migrated to the current
  schema
- **THEN** every migration runs without error, and every row's
  `key`, `assetId`, and `state` are preserved

#### Scenario: Fresh database is created at the current schema
- **WHEN** a database is created from scratch
- **THEN** it has the `assetId` index and the `destinationPath` index, no `updatedAt`, `attempt`, `eventId` or
  `absent` column, and needs no migration step

#### Scenario: Adding absent preserves the rows
- **WHEN** a v6 database holding a `COMPLETED` row is migrated to the current schema
- **THEN** the row survives with its `key`, `assetId`, `state` and manifest detail intact, so it still
  suppresses re-upload and still projects into the manifest

#### Scenario: An UPLOADED row is settled by the migration
- **WHEN** a v8 database holding a row whose stored state is `UPLOADED` is migrated to the current schema
- **THEN** that row reads back `COMPLETED` with every other surviving column intact, and `aggregates()` counts
  its asset completed

#### Scenario: The rewrite touches nothing else
- **WHEN** a v8 database holding `DISCOVERED`, `REQUESTED`, `COMPLETED` and `FAILED` rows is migrated to the
  current schema
- **THEN** the `DISCOVERED`, `REQUESTED` and `COMPLETED` rows keep their state, and the `FAILED` row reads
  `DISCOVERED` (by `10.sqm`)

#### Scenario: The index migration preserves every row and creates no work
- **WHEN** a v9 database that lacks the `destinationPath` index — the shape produced by upgrading through
  the column-adding migration — and holds `DISCOVERED`, `REQUESTED` and `COMPLETED` rows is migrated to
  the current schema
- **THEN** the `destinationPath` index exists, every row keeps its key, state and every other surviving
  column, and no row becomes upload work

#### Scenario: The index migration does not fail on a database that already has the index
- **WHEN** a v9 database **created fresh** from the `CREATE` statements — which already carry the
  `destinationPath` index — is migrated to the current schema
- **THEN** the migration completes without error, the index is still present exactly once, and every row
  is untouched apart from the columns `10.sqm` drops

#### Scenario: A FAILED row is returned to the work source by the migration
- **WHEN** a v10 database holding a `FAILED` row with manifest detail and a destination path is migrated to
  the current schema
- **THEN** the row reads back `DISCOVERED` with its `key`, `assetId`, manifest detail and destination path
  unchanged, and the work-source read returns it

#### Scenario: The shrink leaves every other row's state alone
- **WHEN** a v10 database holding `DISCOVERED`, `REQUESTED` and `COMPLETED` rows is migrated to the current
  schema
- **THEN** each keeps its state and every surviving column, and no row is settled or deleted

#### Scenario: A row left marked absent becomes reachable
- **WHEN** a v10 database holds a `COMPLETED` row whose `absent` mark an earlier build set, and it is migrated
  to the current schema
- **THEN** the row is returned by the manifest projection and counted by the aggregate read

#### Scenario: The drops succeed on both shapes of the population
- **WHEN** `10.sqm` runs on a v10 database created fresh from the `CREATE` statements, and on one upgraded
  through the chain
- **THEN** it completes without error on both, and neither retains an `attempt`, `eventId` or `absent` column

### Requirement: Prune operations are writer-only

The key-scoped delete (`deleteKeys`) SHALL be exposed on
`LedgerWriter` (delegating to the backend) and SHALL NOT be exposed on any other app-facing ledger
surface. It is a sync write by the single ledger writer, not the app-side `clear()` reset, and at
the writer layer it consults no engine state first. Because only the engine's
composition root constructs a `LedgerWriter`, prune access is confined to the single-writer process,
preserving the single-writer invariant.

`deleteKeys(keys)` SHALL delete exactly the rows whose key is among the arguments, whatever their state, and
SHALL write nothing and signal nothing when none of them has a row. It SHALL accept more keys than one storage
statement binds.

**There is no absence mark.** A departed asset's rows are deleted (see "Deletion is a presence diff over an
authoritative walk"), never marked, and the column that once held a mark was dropped by `10.sqm` (see "Ledger
schema migration"). There is therefore no mark to set, no mark to clear, and no per-cycle sweep.

#### Scenario: Writer deletes named keys

- **WHEN** a `LedgerWriter` records rows `X-primary.heic` and `Y-primary.heic` and then calls
  `deleteKeys({"X-primary.heic"})`
- **THEN** `entry("X-primary.heic")` returns nothing, `entry("Y-primary.heic")` is unchanged, and `changes`
  signals once

#### Scenario: Deleting keys that have no row writes nothing

- **WHEN** `deleteKeys` is called with keys none of which has a row
- **THEN** no row changes and `changes` does not signal

#### Scenario: Prune operations are absent from the non-writer surface

- **WHEN** a component holds the ledger only as a `LedgerStore` reader (no writer)
- **THEN** `deleteKeys` is not part of its sanctioned surface — it reaches the backend
  only through the root-constructed `LedgerWriter`

### Requirement: Pending-resource read

`LedgerStore` SHALL expose a read of the **non-done** rows as `(assetId, key)` pairs (the
backlog), so a status projection can group outstanding resources by photo without materializing the
whole table. The read SHALL return exactly the rows whose `state` is not in the done-state set and SHALL
interpret nothing else (the backend remains a dumb row store). On the SQLDelight backend it SHALL be
a single query taking that set as a bound parameter (`SELECT assetId, key FROM ledgerRow WHERE state NOT IN
:doneStates`) — never a query carrying a state literal of its own.

#### Scenario: Returns only outstanding rows

- **WHEN** asset `A` has two `COMPLETED` rows and asset `B` has one `REQUESTED` and one `DISCOVERED` row,
  and the pending-resource read is called
- **THEN** it returns only `B`'s two rows (`B`'s `REQUESTED` and `DISCOVERED` keys), each paired with
  assetId `B`, and none of `A`'s

#### Scenario: Empty when nothing is outstanding

- **WHEN** every row is `COMPLETED`
- **THEN** the pending-resource read returns no rows

### Requirement: Event-independent key

The ledger key SHALL be the **bare resource filename** (`<assetId>-<role>.<ext>`), carrying no event
scoping. Because the key is event-independent, a `COMPLETED` row recorded while one event is
configured stays valid and continues to read as `COMPLETED` after the configured event changes — the
ledger neither keys, **reads**, nor **records** by event: no dedup decision, aggregate, backlog read, or skip
consults an event, and a row carries no event id at all. This is what lets cross-event dedup come purely from the reconcile seed source (a `resetTo`
clear-and-seed from the device-global per-device listing) without any ledger key change.

#### Scenario: A COMPLETED row stays valid after the configured event changes
- **WHEN** a resource is recorded `COMPLETED` under one event and the configured event later changes
- **THEN** `get`/`entry` for that bare filename still returns the `COMPLETED` row, unaffected by the event change

#### Scenario: The key carries no event scoping
- **WHEN** two configured events would reference the same resource
- **THEN** they resolve to the **same** ledger key (the bare filename), so a single `COMPLETED` row serves both

#### Scenario: A row records no event
- **WHEN** a row is recorded while one event is joined and read while another is
- **THEN** the row carries nothing that names either event, and the engine's decision for it (skip on
  `COMPLETED`/`REQUESTED`, work on `DISCOVERED`/no row) is the same under both

### Requirement: The ledger row carries the manifest's presentation detail

Each ledger row SHALL carry, in addition to its dedup key and upload state, the fields the device manifest
requires to name a resource: the asset's `creationDate`, and per resource its `role`, `contentType`, and
human `filename`. These fields make the ledger the single durable, deletion-aware record of the device's
in-event resources, so the device manifest can be projected from it (capability `device-manifest`) rather
than maintained in a parallel accumulator that duplicated the same asset set. The dedup key is unchanged.

A row SHALL carry this detail from the moment it is first recorded, not only once its upload completes: the
manifest declares intent, so a `DISCOVERED` row must already be able to name its resource. The discovery
walk supplies every field, so no additional platform read is required.

The `LedgerStore` read that serves the projection SHALL NOT be state-scoped, and its name SHALL NOT claim a
state. It SHALL return every row, leaving admission to the membership's policy.

#### Scenario: A row names its resource fully as soon as it is recorded

- **WHEN** the discovery walk admits a resource and a `DISCOVERED` row is recorded for it
- **THEN** the row carries `creationDate`, `role`, `contentType`, and `filename` sufficient to build the
  resource's device-manifest entry with no additional PhotoKit read

#### Scenario: The manifest read is not state-scoped

- **WHEN** the projection reads the rows it lists
- **THEN** the read returns rows in every state, and excludes none

### Requirement: Requested-state reset

`LedgerStore` SHALL provide `demoteRequested()`: a bulk state change of **every row whose state is
`REQUESTED`** to `DISCOVERED`, leaving every other field of those rows, and every `DISCOVERED` and `COMPLETED`
row, untouched. It SHALL emit exactly one `changes` signal on success (like `clear`/`resetTo`). On the
SQLDelight backend it SHALL be a single `UPDATE … SET state = 'DISCOVERED' WHERE state = 'REQUESTED'`. There SHALL
be no operation that deletes rows by state: `clearRequested()` is removed.

`demoteRequested` is an **app-side reset-family** operation — in the same family as `clear()` and `resetTo()`,
**not** a per-key record operation. It SHALL be callable on the `LedgerStore` **without** a `LedgerWriter`, so a
non-writer holder of the backend — the app process on iOS ≥26.1, where the extension is the one recording
process — may invoke it without breaching the **single-record-writer invariant**. It applies no precedence and
reads nothing first; it is one storage statement.

It is the recovery for `REQUESTED` rows that **no transfer can settle any more**: the engine never re-issues a
`REQUESTED` key, so without it such a photo is abandoned. Its canonical use is the iOS ≥26.1 PhotoKit tier's
re-register, after a disable has wiped every in-flight OS job at once (`ios-photokit-upload`). A platform whose
transfers can be enumerated recovers precisely instead (`ios-url-session-upload`).

It demotes rather than deletes because a `DISCOVERED` row **needs a job** (see "The DISCOVERED state and the ledger
as the upload work source"): the ledger's own work read returns it on the next cycle, so the recovery
depends on no walk. A deleted row could only return through a walk that reads the asset's resources again,
which a fully-recorded asset's walk skips (see "A walk re-reads only the assets the ledger does not fully
know"). Demoting also keeps the row's recorded detail — `assetId`, role, content type, destination — which a
deletion discarded and a rediscovery had to re-derive.

#### Scenario: demoteRequested returns only REQUESTED rows to DISCOVERED

- **WHEN** the store holds a `DISCOVERED`, a `REQUESTED`, and a `COMPLETED` row, and
  `demoteRequested()` is called
- **THEN** the `REQUESTED` row is now `DISCOVERED` with every other field unchanged, and the other two rows are
  unchanged

#### Scenario: demoteRequested emits one change signal

- **WHEN** `demoteRequested()` succeeds over a store containing at least one `REQUESTED` row
- **THEN** exactly one `changes` signal is emitted, so a watcher re-reads the now-current truth

#### Scenario: A demoted row is returned by the work read without a walk

- **WHEN** a key is `REQUESTED`, `demoteRequested()` runs, and the work source is read with no discovery
- **THEN** the row is among the rows needing a job, so the next cycle re-creates its upload without
  re-reading the asset's resources

### Requirement: Guarded terminal write

`TransferRecord` — which `LedgerStore` extends — SHALL declare `markTerminal(key, outcome): Boolean` — a
**single guarded statement** that sets a row's state **only while that row is still `REQUESTED`**, and answers
whether it applied. On the SQLDelight backend it SHALL be one
`UPDATE … WHERE key = :key AND state = 'REQUESTED'` whose applied/not-applied answer is read inside that
statement's own transaction.

`outcome` SHALL be a `TerminalOutcome` — `COMPLETED` or `FAILED`, declared in `:domain` `model/` — and not a
`LedgerState`. The outcome names what the **platform** reported; the state it records is the ledger's answer:
`COMPLETED` records `COMPLETED` — the platform reported the upload succeeded, and no further work is owed for
the key — and `FAILED` records `DISCOVERED`, returning the row to the work source (see "The DISCOVERED state and
the ledger as the upload work source"). This is the
one record operation a platform callback reaches through `TransferRecord` rather than through the writer (see
"Reader and writer capability split"), which is why the set it may record is fixed by its type rather than by
convention: a callback SHALL NOT be able to claim that a job exists (`REQUESTED`) through it.

It SHALL be **non-suspending**, so a platform callback that cannot call a suspending function may record
through it directly.

The guard is the operation's purpose, not a defence: two writers reach this row with no shared lock — a
platform callback on the platform's own queue, and the upload cycle on the composition lane — and a
read-then-write pair is not atomic against the one that does not take the lock. Every other column
(`assetId`, the manifest detail, and the destination path) SHALL be preserved by the statement rather than
re-supplied by the caller.

A write that applies to no row SHALL be reported to the caller and **SHALL NOT be silent**: "the row moved
on" and "this fact was recorded" have different consequences.

#### Scenario: A REQUESTED row is flipped

- **WHEN** `markTerminal(key, COMPLETED)` is called for a row whose state is `REQUESTED`
- **THEN** the row becomes `COMPLETED`, every other column is unchanged, and the call answers that it applied

#### Scenario: A failed upload returns its row to the work source

- **WHEN** `markTerminal(key, FAILED)` is called for a row whose state is `REQUESTED`
- **THEN** the row becomes `DISCOVERED`, every other column is unchanged, the call answers that it applied,
  and the work-source read returns the row

#### Scenario: A row that moved on is not clobbered

- **WHEN** `markTerminal(key, FAILED)` is called for a row whose state is no longer `REQUESTED`
- **THEN** no row is changed and the call answers that it did not apply

#### Scenario: An absent row is reported, not assumed

- **WHEN** `markTerminal` is called for a key with no row
- **THEN** no row is created and the call answers that it did not apply

#### Scenario: A job's existence cannot be claimed through it

- **WHEN** a caller attempts to record `REQUESTED` through `markTerminal`
- **THEN** the build fails, because the parameter's type admits only the outcomes `COMPLETED` and `FAILED`

### Requirement: The DISCOVERED state and the ledger as the upload work source

`LedgerState` SHALL carry a `DISCOVERED` value meaning **the resource's asset was admitted and its key needs an
upload job: no job is in flight for it and its bytes are not on the backend**. It SHALL be recorded for
every resource a cycle's walk admitted and the engine judged to be new work, **before** any upload
job is created for that cycle. It SHALL also be what a failed upload returns its row to — through the engine's
failure record, a transport's terminal write, the stranded reconciliation, or `demoteRequested` — because a
failure and a never-attempted discovery are the same fact to a producer. There is no separate failed state:
the engine retries forever with no attempt budget, so "an attempt was already made" decides nothing.
`LedgerState` therefore has exactly three values: `DISCOVERED`, `REQUESTED` and `COMPLETED`.

The ledger SHALL be the upload cycle's **source of work**: a producer SHALL enqueue from the ledger's
rows rather than from the walk's return value, so a cycle can make progress on work it already knows
about whatever the walk returns. Every walk is a full enumeration, but it re-reads resources only for assets
the ledger does not fully know (see "A walk re-reads only the assets the ledger does not fully know"), so a
row that needs a job is found by this read, never re-derived by the walk. The `LedgerStore` SHALL expose a
state-scoped read of the rows that need a job, and it SHALL return the `DISCOVERED` rows — whether never
attempted or returned there by a failure.

A row needing a job records that the policy admitted its asset **when the row was written**, which is not
the same fact as the membership's *current* admission (`photo-selection-policy`). The cycle SHALL
therefore admit the rows this read returns before resolving or enqueuing any of them, and any bound on
how much work one cycle takes SHALL be applied to the **admitted** rows — bounding what a cycle
**resolves**, never what it reads. A bound applied to the read instead can starve: rows are returned in a
stable key order, so excluded rows sorting ahead of admitted ones would fill the batch on every cycle and
admitted work further down would never be reached. What the bound exists to protect is the platform
round-trip and, on the app-driven tier, the staged temp file — both of which follow the admitted rows.

A row this read returned whose key the platform resolves to **nothing** SHALL have that row, and only that
row, deleted (see "Deletion is a presence diff over an authoritative walk"). Its asset has left the library,
or, under a partial grant, left the selection. Either way it can no longer be uploaded, and a row that
still needs a job for it would be offered on every cycle. Deleting by key rather than by asset is what
leaves the asset's settled rows alone: this read selects rows by key.

`DISCOVERED` SHALL NOT be a done state, so a row in it counts toward the backlog everywhere. It SHALL
nonetheless be **included** in the device-manifest projection: the manifest declares what this device
intends to provide, and a resource the walk found and the policy admitted is precisely that (capability
`device-manifest`). It SHALL NOT be a stranding candidate: the stranded reconciliation reads `REQUESTED`
keys only, and surfacing a row that has no job as a lost transfer would record a failure that did not
happen.

#### Scenario: A discovered resource is recorded before any job exists

- **WHEN** a cycle's walk admits a resource the engine judges to be new work
- **THEN** a `DISCOVERED` row is recorded for that resource's key before `createJob` is called for it

#### Scenario: A top-up enqueues from the ledger, not from the walk's output

- **WHEN** a cycle runs with rows in `DISCOVERED` and its walk returns no asset it has not
  already recorded
- **THEN** it resolves those rows' keys and enqueues them, rather than treating a walk with nothing new as
  no work

#### Scenario: A failed row is re-enqueued without re-reading its asset

- **WHEN** a row's upload failed, returning it to `DISCOVERED`, and its asset is fully recorded, so the walk
  skips its resources
- **THEN** the next cycle re-enqueues it from the ledger, rather than waiting for the walk to re-derive it

#### Scenario: The ledger has three states

- **WHEN** the `LedgerState` values are listed
- **THEN** they are exactly `DISCOVERED`, `REQUESTED` and `COMPLETED`, and `DISCOVERED` is the only state that
  needs a job

#### Scenario: A row that no longer resolves is deleted by key

- **WHEN** a `DISCOVERED` row's key resolves to nothing at enqueue, while a sibling row of the same asset is
  `COMPLETED`
- **THEN** the unresolved row is deleted, the `COMPLETED` sibling is untouched, and no upload is attempted
  for the deleted key

#### Scenario: A discovered row is backlog AND manifest

- **WHEN** a row is `DISCOVERED`
- **THEN** it counts toward the pending aggregate and the pending-resource read, and it appears in the
  device-manifest projection for every membership whose policy admits its asset

#### Scenario: A discovered row is never stranded

- **WHEN** the stranded reconciliation runs while a `DISCOVERED` row exists with no live transfer
- **THEN** that row is not surfaced as a lost transfer and is not written

#### Scenario: The batch bounds the resolved work, not the read

- **WHEN** the rows needing a job exceed one cycle's batch and some of them are excluded by the
  membership's current policy
- **THEN** the cycle resolves at most one batch of **admitted** rows, and the excluded ones neither
  consume the batch nor prevent admitted rows from being enqueued

#### Scenario: An excluded row is retained, not pruned

- **WHEN** the membership's policy stops admitting an asset whose row needs a job
- **THEN** the row is left in the ledger untouched — no state change, no prune — so widening the policy
  again re-admits it and the next cycle enqueues it with no re-read of its asset

## REMOVED Requirements

### Requirement: Event provenance and the backfill sweep
**Reason**: The `eventId` column was provenance that no read consulted — no dedup decision, aggregate, backlog
read, manifest projection or album placement filtered or grouped by it — so it decided nothing, while costing a
parameter on every record operation, a required argument on the engine, and an `UPDATE` on every settled cycle.
`10.sqm` drops the column (see "Ledger schema migration"). Decision record: `changes/shrink-the-ledger-row`.
**Migration**: None needed by any caller. Record operations and reconciliation seeds stop supplying an event
id; `backfillEventId` and the cycle's sweep are deleted with the column. The staged-revert posture the
column's `DEFAULT ''` supported is replaced by the roll-forward rollback stated in "Ledger schema migration".
