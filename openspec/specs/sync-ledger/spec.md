# sync ledger Specification

## Purpose

The engine's durable per-key upload memory: a backend storage seam (dumb row store that signals
its own changes), a three-way capability split — reader (per-key, engine-facing), writer
(records, single per platform, codified by construction), watcher (aggregate stream,
status-facing) — and self-contained idempotent record operations. The ledger is what makes
skipping provable, reports absorbable (at-least-once), full re-enumeration harmless, and status
a read-only projection.

**Single record-writer is the load-bearing invariant**, and its process placement is a platform binding, not
a property of this seam: on iOS ≥26.1 the upload extension is the sole writer and the app holds only a reader
and a watcher; on iOS 18–26.0 no extension exists, so the app holds it. Codifying the split as three
capabilities — reader, writer, watcher — makes the invariant a compile-time fact rather than a convention.

Decision record: `changes/archive/2026-06-12-sync-engine-ledger`.

The **Lifecycle transitions never clear the ledger** requirement was added in
`changes/archive/2026-07-12-fix-app-driven-upload-lifecycle`. The `eventId` provenance column, the
`4.sqm` migration, and the backfill sweep were added in
`changes/archive/2026-07-18-add-ledger-event-provenance` (migration step 11b).

The `DISCOVERED` state, the `needsJob` classification beside `isDone`, and the bounded work-source
read that together make the ledger the upload cycle's source of work were added in `changes/archive/2026-08-27-fix-cap-truncation-loop`.

The guarded record write that never overwrites a settled row (replacing the unconditional `put`), and
`markPresent`, which listed a restored photo again, were added in
`changes/archive/2026-09-15-record-never-overwrites-settled-row` — the `ON CONFLICT` precedence re-examination the
original decision record's D7 deferred to the arrival of a second writer.
Deletion as a presence diff over an authoritative, in-window walk, the key-scoped delete that replaced
`markAbsent`/`markPresent`, the retired absence mark and its sweep, and the walk's read skip with its atomic batch
record came from `changes/archive/2026-09-21-always-full-enumerate`, which removed the discovery cursor.

The `UPLOADED` state and its promotion (added in `changes/archive/2026-08-26-fix-lost-upload-acks`) were
retired, the guarded terminal write narrowed to a `TerminalOutcome`, and the `8.sqm` rewrite added in
`changes/archive/2026-09-15-retire-uploaded-state`.
## Requirements
### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerStore` interface with the row
read `get(key): LedgerEntry?`, the guarded record write `recordUnlessSettled(entry): Boolean` (see
"Record operations" — a single-row upsert that never overwrites a row in a done state, and answers whether
it applied), the guarded terminal write
`markTerminal(key, outcome): Boolean` (see "Guarded terminal write"), the state-scoped read of `REQUESTED` keys, the bounded state-scoped read of
rows that **need a job** (see "The DISCOVERED state and the ledger as the upload work source"), the
manifest projection read and its detail backfill, the aggregate read
`aggregates(): LedgerAggregates`, a change signal `changes: Flow<Unit>`, `clear()` — a
delete-all reset, `demoteRequested()` — mark every `REQUESTED` row `FAILED` (see "Requested-state reset"), `resetTo(entries)` — an **atomic**
delete-all-then-insert-all replacement, the key-targeted delete `deleteKeys(keys)` — delete exactly the
rows whose `key` is among the arguments and no other — the batch record write `recordAllUnlessSettled(entries)`
(see "A walk re-reads only the assets the ledger does not fully know"), the retired absence mark's sweep
`clearAbsenceMarks()` (see "Prune operations are writer-only"), and the provenance sweep
`backfillEventId(eventId)` (see "Event provenance and the backfill sweep").

There is deliberately **no** unconditional per-row upsert (`put`). It was removed when the record path became
guarded: with no production caller left, it could only serve as an unguarded door for the next production
write. Tests seed a store through the guarded record write or `resetTo`, exactly as production writes it.

There is deliberately **no** promotion and **no** uploaded-row read. A successful upload is recorded
`COMPLETED` at the moment the platform reports it, so no state exists between "the bytes are stored" and
"nothing further is owed".

Backends SHALL store the fields of an applied write verbatim (no interpretation, no clocks of their own). The
**only** precedence a backend applies is the one each named guarded operation states — the record write's
done-state guard and `markTerminal`'s `REQUESTED` guard — and each SHALL be
enforced inside the storage statement itself, never by a read followed by a write. The reset family SHALL
apply no precedence at all. A `LedgerEntry` SHALL carry `key`, `assetId`, `state` (`DISCOVERED` |
`REQUESTED` | `COMPLETED` | `FAILED`), `attempt`, and `eventId` — the event that was
joined when the row was recorded. `clear()`, `demoteRequested()`, `resetTo`, an applied `deleteKeys`, an
applied record write (a batch record write that applied to any row signals once for the whole batch), an
applied `clearAbsenceMarks`, and an applied
`markTerminal` SHALL each remove (and, for `resetTo`, then insert) or
update the matching rows and signal `changes` **once** (so watchers re-read the
now-current truth).
`clear()`, `demoteRequested()`, `resetTo`, `deleteKeys`, and `clearAbsenceMarks` are **reset/bulk** operations, not the
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
selection policy"), and neither returns with presence-driven deletion. Deletion is **key-scoped**: every
caller of `deleteKeys` names exactly the rows it holds evidence about — a key that failed to resolve, or a
row an authoritative walk did not return (see "Deletion is a presence diff over an authoritative walk") —
so no operation can reach the settled siblings of a row it did not judge. Several resources of one photo
share an `assetId` and hold per-key states, so an asset-scoped delete driven by a key-grained read would
reach rows the read never selected.

#### Scenario: A recorded entry round-trips
- **WHEN** `recordUnlessSettled(entry)` is called for a key with no row, and then `get(entry.key)`
- **THEN** the write reports applied, and the returned entry equals the one recorded, field for field —
  including `assetId` and `eventId`

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
- **WHEN** asset `A` has two `COMPLETED` rows and asset `B` has one `COMPLETED` and one `FAILED` row
- **THEN** `aggregates()` answers `pending = 1, completed = 1` (A complete, B pending)

#### Scenario: A photo counts complete as soon as its last upload is recorded
- **WHEN** an asset's only `REQUESTED` row is recorded `COMPLETED` through `markTerminal`, and no cycle has
  run since
- **THEN** `aggregates()` counts that asset completed

### Requirement: Change signal

`LedgerStore.changes` SHALL emit `Unit` after every write that changed the store — every applied record
write, applied guarded write, and reset/bulk operation. A record write the guard declined changed nothing and
SHALL NOT signal. A ding carries no payload and
promises nothing beyond "re-read the truth" — consumers MUST treat it as a level trigger (conflation,
duplicate dings, and signals missed while busy are all safe because every re-read queries current state).
The signal is **in-process only**: the ledger is the extension's private upload memory and has no
cross-process watcher, so the backend SHALL NOT post any cross-process (Darwin) notification, and there is
no app-process observer to merge. The seam itself does not change.

#### Scenario: An applied record dings

- **WHEN** a collector is active on `changes` and a record write applies
- **THEN** the collector receives an emission

#### Scenario: A declined record does not ding

- **WHEN** a collector is active on `changes` and a record write is declined because the row is in a done state
- **THEN** the collector receives no emission

#### Scenario: No cross-process notification is posted

- **WHEN** the extension process records within a `process()` cycle
- **THEN** no cross-process (Darwin) notification is posted, because no other process observes the ledger

### Requirement: Reader and writer capability split

The ledger SHALL expose a concrete shared `LedgerWriter` carrying both the record operations and the
per-key query (`entry(key): LedgerEntry?`). Record and query semantics SHALL be implemented once in
this shared class, delegating storage to the injected `LedgerStore`. There SHALL be no separate
reader type: the writer is constructed only by the composition root that owns the engine (one per
platform), and components that must not record are simply never handed a writer — app-side read
access goes through `LedgerStore`'s read operations (`aggregates()`, per `sync-status`), never
through a writer instance.

**The invariant is that exactly one PROCESS records**, and its process placement is a platform binding — the
extension on iOS ≥26.1, the app on iOS 18–26.0. Handing a writer instance only where recording is intended is
the **mechanism** that codifies it, not the invariant itself. That mechanism is deliberately relaxed for one
operation: `markTerminal` (see "Guarded terminal write") is declared on **`TransferRecord`** — a narrow
interface `LedgerStore` extends, carrying only `markTerminal` and the read `entryForDestination` (see "The
ledger records the destination a job was sent to") — because the party the platform tells that an upload
terminated is a platform callback inside the record-writing process, and it cannot suspend. The invariant
holds — that callback belongs to the one recording process — while the type-level codification does not cover
it. A spec or a review that reads the type-level rule as the invariant will reach the wrong conclusion about
this call, which is why both are stated.

A **transport** — an implementation of the upload transfer lifecycle (`BackgroundTransfer`) — SHALL receive a
`TransferRecord` and SHALL NOT receive a `LedgerStore`. What a transport may touch in the ledger is therefore
exactly the one guarded terminal write and the one destination lookup; every other read and write, including
the decision which in-flight rows a transport has lost, belongs to the cycle.

No record operation other than `markTerminal` SHALL be added to `TransferRecord` or to `LedgerStore` on this
argument; a further record operation belongs on the writer.

#### Scenario: Writer reads what it wrote

- **WHEN** a `LedgerWriter` records an entry and `entry(key)` is called on the same instance
- **THEN** the recorded entry is returned

#### Scenario: Record access exists only where the writer is constructed

- **WHEN** a component is composed without receiving the root's `LedgerWriter`
- **THEN** it has no record operation available beyond `markTerminal` — it can otherwise read the ledger
  only through `LedgerStore`'s read operations

#### Scenario: One process records

- **WHEN** the platform callback records a terminal upload through `TransferRecord` and the cycle records
  through the `LedgerWriter`
- **THEN** both are inside the single record-writing process for that tier, and no second process records

#### Scenario: A transport holds only the narrow surface

- **WHEN** a transport adapter is composed
- **THEN** it is handed a `TransferRecord`, and no other ledger read or write is reachable from it

### Requirement: Record operations
`LedgerWriter` SHALL provide `recordDiscovered`, `recordRequested`, and
`recordFailed`. Each SHALL
upsert a complete, self-contained entry for the key (assetId, state, attempt, eventId as supplied
by the caller) through the backend's guarded record write, `recordUnlessSettled` — one storage
statement per record.
`assetId` and `eventId` are supplied positionally as `recordX(key, assetId, attempt, eventId)` (the
writer stays on primitives, decoupled from the engine's `Resource`; the eventId is per-call because
the writer outlives any one membership — it is constructed at composition time, while the joined
event arrives per cycle with the gate). `recordDiscovered` additionally carries the resource's
manifest detail, because the walk is the only reader of a capture date and a row recorded without one
is excluded from every projection until a later walk backfills it. The writer records no timestamp and reads
no clock — the engine, writer, and backends are all clock-free. Duplicate record operations with
identical arguments SHALL converge on assetId, state, attempt, and eventId.

A record operation SHALL NOT overwrite a row whose current state is in the **done-state set** (see "The
done-state set is decided in Kotlin"). The guard SHALL be enforced **inside the storage statement** — on the
SQLDelight backend one `INSERT … ON CONFLICT(key) DO UPDATE … WHERE state NOT IN :doneStates`, with the set
bound as a parameter — and SHALL NOT depend on a read the writer made first. A read-then-write is not atomic
against a second writer, and a late record over a settled row would require a job for bytes the backend
already holds. Transitions between non-done states (a retry `FAILED → REQUESTED`, a stranded transfer
`REQUESTED → FAILED`) SHALL still apply. A record the guard declined SHALL NOT be silent: the writer SHALL log
it, naming the key and the refused state.

There is **no** writer operation that records `COMPLETED`. A completed upload is a fact the platform reports,
recorded through the guarded `markTerminal` (see "Guarded terminal write"); a resource already known to be
stored is seeded `COMPLETED` by the re-join reconciliation's `resetTo`.

`recordDiscovered` SHALL NOT overwrite a row that already exists in any other state: a resource that
is `REQUESTED` or `COMPLETED` is not new work, and re-recording it would either duplicate
an in-flight job or discard a fact about the world.

#### Scenario: Discovered entry
- **WHEN** `recordDiscovered` is called for a resource whose key has no row
- **THEN** `entry(key)` has state `DISCOVERED` with that resource's assetId and the supplied eventId,
  and carries the manifest detail the resource was discovered with

#### Scenario: Discovering an already-recorded key changes nothing
- **WHEN** `recordDiscovered` is called for a key whose row is `REQUESTED` or `COMPLETED`
- **THEN** the row is unchanged

#### Scenario: Requested entry
- **WHEN** `recordRequested(key, assetId, attempt, eventId)` is called
- **THEN** `entry(key)` has state `REQUESTED` with that assetId, attempt, and eventId

#### Scenario: Failed entry
- **WHEN** `recordFailed(key, assetId, attempt, eventId)` is called
- **THEN** `entry(key)` has state `FAILED` with that assetId, attempt, and eventId

#### Scenario: Recording converges
- **WHEN** the same record operation is applied twice with identical arguments
- **THEN** `entry(key)` has the same assetId, state, attempt, and eventId as after one application

#### Scenario: A settled row survives every record operation
- **WHEN** `recordRequested` or `recordFailed` is called — with any attempt and eventId — for a
  key whose row is `COMPLETED`
- **THEN** the row is unchanged, field for field, and the backend reports the write as not applied

#### Scenario: A retry still re-requests a failed row
- **WHEN** `recordRequested` is called for a key whose row is `FAILED`
- **THEN** `entry(key)` has state `REQUESTED` with the supplied attempt

#### Scenario: A stranded transfer still fails a requested row
- **WHEN** `recordFailed` is called for a key whose row is `REQUESTED`
- **THEN** `entry(key)` has state `FAILED` with the supplied attempt

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
`key TEXT PRIMARY KEY, assetId TEXT NOT NULL, state TEXT NOT NULL, attempt INTEGER NOT NULL,
eventId TEXT NOT NULL DEFAULT '', absent INTEGER NOT NULL DEFAULT 0`
plus an index on `assetId` (backing the `assetId`-grouped aggregate). The `absent`
column is **retired and unwritten** (see "Prune operations are writer-only"): no operation sets it, and
`clearAbsenceMarks` clears what an earlier build set. It stays in the schema until a later migration drops
it; its `DEFAULT 0` SHALL remain present in **both** the migration and the CREATE statement, like
`eventId`'s. Reads that answer *what does this device hold or share* SHALL keep excluding marked rows. `state`
SHALL be a SQLDelight typed column (`AS LedgerState` via the built-in enum adapter); adapter wiring
SHALL be hidden in a single factory function so construction sites never see it. The schema carries
no timestamp column. The `eventId` column's `DEFAULT ''` SHALL be present in **both** the migration
and the CREATE statement (the SQLDelight migration-verify task proves the two schemas identical),
and SHALL NOT be removed while any shipped build may write a 4-column row (see "Event provenance
and the backfill sweep", staged revert). The record write SHALL be a single guarded SQL upsert statement
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

### Requirement: Prune operations are writer-only

The key-scoped delete (`deleteKeys`) and the absence-mark sweep (`clearAbsenceMarks`) SHALL be exposed on
`LedgerWriter` (delegating to the backend) and SHALL NOT be exposed on any other app-facing ledger
surface. Each is a sync write by the single ledger writer, not the app-side `clear()` reset, and at
the writer layer it consults no engine state first. Because only the engine's
composition root constructs a `LedgerWriter`, prune access is confined to the single-writer process,
preserving the single-writer invariant.

`deleteKeys(keys)` SHALL delete exactly the rows whose key is among the arguments, whatever their state, and
SHALL write nothing and signal nothing when none of them has a row. It SHALL accept more keys than one storage
statement binds.

**The absence mark is retired.** No operation SHALL set a row's `absent` mark: a departed asset's rows are
deleted (see "Deletion is a presence diff over an authoritative walk"), not marked. The `absent` column
SHALL remain in the schema, unwritten, until a later migration removes it, and the reads that exclude
marked rows keep that exclusion. `clearAbsenceMarks()` SHALL clear the mark of every row an earlier build
marked — one idempotent statement, whatever the row's state, leaving every other field untouched — and SHALL
signal `changes` only when it cleared a mark. The upload cycle SHALL run it once per cycle, beside the
provenance sweep, so a row an earlier build marked is reachable again by the work read and by the walk's
deletion. Without it such a row would be excluded from every read that matters, and nothing could ever
reach it again. It SHALL NOT be carried by a schema migration: a migration raises the schema version,
which an older binary refuses to open.

#### Scenario: Writer deletes named keys

- **WHEN** a `LedgerWriter` records rows `X-primary.heic` and `Y-primary.heic` and then calls
  `deleteKeys({"X-primary.heic"})`
- **THEN** `entry("X-primary.heic")` returns nothing, `entry("Y-primary.heic")` is unchanged, and `changes`
  signals once

#### Scenario: Deleting keys that have no row writes nothing

- **WHEN** `deleteKeys` is called with keys none of which has a row
- **THEN** no row changes and `changes` does not signal

#### Scenario: The sweep clears marks an earlier build wrote

- **WHEN** a row carries the `absent` mark from an earlier build, and the writer calls `clearAbsenceMarks()`
- **THEN** the row is unmarked with every other field unchanged, it is returned again by the reads that
  exclude marked rows, and `changes` signals once

#### Scenario: The sweep on an unmarked ledger writes nothing

- **WHEN** `clearAbsenceMarks()` runs over a ledger with no marked row
- **THEN** no row changes and `changes` does not signal

#### Scenario: Prune operations are absent from the non-writer surface

- **WHEN** a component holds the ledger only as a `LedgerStore` reader (no writer)
- **THEN** `deleteKeys` and `clearAbsenceMarks` are not part of its sanctioned surface — they reach the backend
  only through the root-constructed `LedgerWriter`

### Requirement: Pending-resource read

`LedgerStore` SHALL expose a read of the **non-done** rows as `(assetId, key)` pairs (the
backlog), so a status projection can group outstanding resources by photo without materializing the
whole table. The read SHALL return exactly the rows whose `state` is not in the done-state set and SHALL
interpret nothing else (the backend remains a dumb row store). On the SQLDelight backend it SHALL be
a single query taking that set as a bound parameter (`SELECT assetId, key FROM ledgerRow WHERE state NOT IN
:doneStates`) — never a query carrying a state literal of its own.

#### Scenario: Returns only outstanding rows

- **WHEN** asset `A` has two `COMPLETED` rows and asset `B` has one `REQUESTED` and one `FAILED` row,
  and the pending-resource read is called
- **THEN** it returns only `B`'s two rows (`B`'s `REQUESTED` and `FAILED` keys), each paired with
  assetId `B`, and none of `A`'s

#### Scenario: Empty when nothing is outstanding

- **WHEN** every row is `COMPLETED`
- **THEN** the pending-resource read returns no rows

### Requirement: Atomic baseline reset

`LedgerStore.resetTo(entries)` SHALL replace the entire store with `entries` in a single atomic
transaction: either all prior rows are removed and all `entries` inserted, or — on failure or
interruption — the store is left unchanged (no partial replacement is ever observable). It SHALL emit
exactly one `changes` signal on success. Entries are stored verbatim (the caller supplies `state`);
`resetTo` performs no clock stamping of its own. On the SQLDelight backend it SHALL execute as one
transaction.

The atomic baseline reset (`resetTo`, the clear-then-seed primitive) **is what rejoin reconciliation
invokes on a re-join** (an event switch, reinstall, or fresh provision). Reconciliation `resetTo`s the
ledger to exactly one `COMPLETED` row per filename in the **per-device** listing, so the clear is what
drops stale/phantom rows (e.g. a `REQUESTED` row whose job never materialized) while the
device-global, event-independent listing re-seeds the same files `COMPLETED` — preserving cross-event
dedup so globally-stored resources never re-upload after a switch. The bare-filename key is what makes
this safe: a re-seeded `COMPLETED` row keys identically across events.

#### Scenario: Interrupted reset leaves the store unchanged
- **WHEN** a `resetTo` transaction fails partway (e.g. an insert errors)
- **THEN** the store retains exactly its pre-call rows and no `changes` signal claims a new baseline

#### Scenario: Reset to a non-empty baseline is observable as a whole
- **WHEN** `resetTo(entries)` succeeds over a previously empty store
- **THEN** `aggregates()` reflects all `entries` at once and `get` returns each supplied entry verbatim

#### Scenario: Reset baseline holds on the SQLDelight backend
- **WHEN** the reset scenarios run against the SQLDelight backend on a JVM sqlite driver
- **THEN** they pass unchanged (a single-transaction replacement, one change signal)

#### Scenario: A re-join resetTo seed preserves cross-event dedup
- **WHEN** the store holds `COMPLETED` rows from a prior event plus a stale non-`COMPLETED` row, and reconciliation `resetTo`s the new event from the device-global per-device listing
- **THEN** the listing re-seeds the still-stored files `COMPLETED` (so none re-upload) and the stale row is dropped by the clear, leaving the ledger as exactly the device's stored files

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

### Requirement: Requested-state reset

`LedgerStore` SHALL provide `demoteRequested()`: a bulk state change of **every row whose state is
`REQUESTED`** to `FAILED`, leaving every other field of those rows, and every `DISCOVERED`, `COMPLETED` and
`FAILED` row, untouched. It SHALL emit exactly one `changes` signal on success (like `clear`/`resetTo`). On the
SQLDelight backend it SHALL be a single `UPDATE … SET state = 'FAILED' WHERE state = 'REQUESTED'`. There SHALL
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

It demotes rather than deletes because a `FAILED` row **needs a job** (see "The DISCOVERED state and the ledger
as the upload work source"): the ledger's own work read returns it on the next cycle, so the recovery
depends on no walk. A deleted row could only return through a walk that reads the asset's resources again,
which a fully-recorded asset's walk skips (see "A walk re-reads only the assets the ledger does not fully
know"). Demoting also keeps the row's recorded detail — `assetId`, role, content type, provenance — which a
deletion discarded and a rediscovery had to re-derive.

#### Scenario: demoteRequested marks only REQUESTED rows FAILED

- **WHEN** the store holds a `DISCOVERED`, a `REQUESTED`, a `COMPLETED`, and a `FAILED` row, and
  `demoteRequested()` is called
- **THEN** the `REQUESTED` row is now `FAILED` with every other field unchanged, and the other three rows are
  unchanged

#### Scenario: demoteRequested emits one change signal

- **WHEN** `demoteRequested()` succeeds over a store containing at least one `REQUESTED` row
- **THEN** exactly one `changes` signal is emitted, so a watcher re-reads the now-current truth

#### Scenario: A demoted row is returned by the work read without a walk

- **WHEN** a key is `REQUESTED`, `demoteRequested()` runs, and the work source is read with no discovery
- **THEN** the row is among the rows needing a job, so the next cycle re-creates its upload without
  re-reading the asset's resources

### Requirement: Lifecycle transitions never clear the ledger

`clear()` SHALL NOT be used as a membership-lifecycle mechanism. No provision, re-provision, event
switch, permission change, direction change, or **leave** SHALL call `clear()` on the ledger
(`upload-lifecycle`, "Upload producer seam has no destructive verb").

The ledger is **device-global dedup state**, not event state: its key is the bare resource filename
with no event scoping (see "Event-independent key"), and leaving an event does not remove the device's
bytes from its storage partition. A `COMPLETED` row therefore stays **true** across a leave, a switch,
and a re-join — and clearing it would force a re-upload of every already-stored resource on the next
join.

The **only** operation that re-baselines the ledger SHALL be `resetTo`, invoked by a triggered
reconciliation against the authoritative per-device listing (`upload-state-reconciliation`). Ledger and
storage may diverge only at a (re)join, and reconciliation — not a lifecycle wipe — is what closes that
divergence.

`clear()` SHALL remain on the `LedgerStore` seam (it is the semantic basis of `resetTo` and is used
by test and harness backends), but it SHALL have no membership-lifecycle caller.

#### Scenario: Leaving an event preserves every ledger row

- **WHEN** the user leaves the currently-joined event
- **THEN** the ledger retains every row, so joining any event afterwards re-uploads nothing already in the device's byte partition

#### Scenario: Re-provisioning preserves every ledger row

- **WHEN** the device switches to a different event
- **THEN** the switch itself clears nothing; only the reconciliation's `resetTo` re-baselines the ledger, from the per-device listing

#### Scenario: Only reconciliation re-baselines the ledger

- **WHEN** the ledger is re-baselined
- **THEN** the re-baseline is a `resetTo` from an authoritative per-device listing, never a lifecycle-driven `clear()`

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
Decision record: `changes/archive/2026-07-18-add-ledger-event-provenance`, D4–D5.

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

### Requirement: The ledger is never pruned by the selection policy

The ledger SHALL record every resource whose bytes are on the backend for an event, and that record
SHALL NOT depend on the membership's current selection policy. A member narrowing their scope changes
**what they share** (capability `device-manifest`); it SHALL NOT change **what they have uploaded**.

No operation SHALL remove a row because the current policy stopped admitting its asset. Doing so
discards the record that suppresses re-upload, which makes a narrowing irreversible: re-widening would
re-upload bytes already present on the backend. In the limit — a membership whose direction excludes
upload, admitting nothing — a policy-derived removal would discard the **entire** event's rows,
defeating the drain requirement (capability `reconfigure-membership`), which exists so that a settled
upload is recorded and re-enabling the direction re-uploads nothing.

Deletion from the **library** is a different fact, and SHALL be decided only by **presence**, never by
admission: a row is removed when an authoritative walk of the library shows its asset is gone (see
"Deletion is a presence diff over an authoritative walk"). The retired retain-live reconcile was fed the
policy-admitted set, which is exactly the conflation this requirement forbids; presence-driven deletion is
fed the walk's whole candidate set and judges only rows inside the policy's window, so narrowing the
policy moves rows **out** of the set it may delete rather than into it.

#### Scenario: A narrowing scope removes no rows
- **WHEN** the membership's capture cutoff is raised and a full enumeration then runs
- **THEN** every ledger row of an asset still in the library is retained, including those for assets now
  outside the range

#### Scenario: Turning the direction off removes no rows
- **WHEN** a contributing membership's direction is turned off and a cycle runs
- **THEN** the event's ledger rows are retained in full, so re-enabling the direction re-uploads nothing

#### Scenario: An origin exclusion removes no rows
- **WHEN** an asset with a `COMPLETED` row is still in the library and the walk returns it, but the policy's
  admission now excludes it (for example, it was added to a denylisted album)
- **THEN** its rows are retained: the walk returned it, so it is present

#### Scenario: Narrow then widen re-lists without re-uploading
- **WHEN** a member narrows their scope, a full enumeration runs, and the member then widens it back
- **THEN** the previously-uploaded assets are listed again and no byte is re-uploaded

#### Scenario: A restored asset re-uploads under its own key
- **WHEN** an asset whose rows an authoritative walk deleted is restored to the library, and the next walk
  returns it
- **THEN** its resources are recorded `DISCOVERED` again and re-uploaded under the same keys; this is the
  accepted cost of storing presence rather than remembering absence

### Requirement: The done-state set is decided in Kotlin

Which `LedgerState` values count as **done** SHALL be decided by a single exhaustive `when` in `:domain`
`model/`, and bound into every state-scoped storage statement as a parameter — never written as a literal inside a
query. On the SQLDelight backend the pending-resource read, the aggregate read, the manifest projection, and the
guarded record write
SHALL each take the done-state set as a bound parameter (`state NOT IN :doneStates` / `state IN
:doneStates`) rather than comparing `state` to `'COMPLETED'`.

Today exactly one state is done (`COMPLETED`), so every read keeps its current meaning. The requirement
exists for the next one: a state added without classifying it SHALL fail to compile, rather than landing
silently on one side of a string comparison.

#### Scenario: A new state must be classified

- **WHEN** a value is added to `LedgerState` and the done-state decision is not updated
- **THEN** the build fails, because the decision is an exhaustive `when` with no `else` branch

#### Scenario: Reads agree on what done means

- **WHEN** the pending-resource read, the aggregate read, and the manifest projection run over the same rows
- **THEN** each classifies every row by the same done-state set, with no query carrying a state literal of
  its own

#### Scenario: The record guard agrees with the reads

- **WHEN** a row counts as done in the aggregate read
- **THEN** the guarded record write declines to overwrite it, and a row the aggregate counts as pending is
  overwritten

### Requirement: Guarded terminal write

`TransferRecord` — which `LedgerStore` extends — SHALL declare `markTerminal(key, outcome): Boolean` — a
**single guarded statement** that sets a row's state **only while that row is still `REQUESTED`**, and answers
whether it applied. On the SQLDelight backend it SHALL be one
`UPDATE … WHERE key = :key AND state = 'REQUESTED'` whose applied/not-applied answer is read inside that
statement's own transaction.

`outcome` SHALL be a `TerminalOutcome` — `COMPLETED` or `FAILED`, declared in `:domain` `model/` — and not a
`LedgerState`, so the only states this write can record are the two an upload can terminate in. This is the
one record operation a platform callback reaches through `TransferRecord` rather than through the writer (see
"Reader and writer capability split"), which is why the set it may record is fixed by its type rather than by
convention. `COMPLETED` means the platform reported the upload succeeded; no further work is owed for the key.

It SHALL be **non-suspending**, so a platform callback that cannot call a suspending function may record
through it directly.

The guard is the operation's purpose, not a defence: two writers reach this row with no shared lock — a
platform callback on the platform's own queue, and the upload cycle on the composition lane — and a
read-then-write pair is not atomic against the one that does not take the lock. Every other column
(`assetId`, `attempt`, `eventId`, and the manifest detail) SHALL be preserved by the statement rather than
re-supplied by the caller.

A write that applies to no row SHALL be reported to the caller and **SHALL NOT be silent**: "the row moved
on" and "this fact was recorded" have different consequences.

#### Scenario: A REQUESTED row is flipped

- **WHEN** `markTerminal(key, COMPLETED)` is called for a row whose state is `REQUESTED`
- **THEN** the row becomes `COMPLETED`, every other column is unchanged, and the call answers that it applied

#### Scenario: A row that moved on is not clobbered

- **WHEN** `markTerminal(key, FAILED)` is called for a row whose state is no longer `REQUESTED`
- **THEN** no row is changed and the call answers that it did not apply

#### Scenario: An absent row is reported, not assumed

- **WHEN** `markTerminal` is called for a key with no row
- **THEN** no row is created and the call answers that it did not apply

#### Scenario: A non-terminal state cannot be recorded

- **WHEN** a caller attempts to record `DISCOVERED` or `REQUESTED` through `markTerminal`
- **THEN** the build fails, because the parameter's type admits only `COMPLETED` and `FAILED`

### Requirement: The DISCOVERED state and the ledger as the upload work source

`LedgerState` SHALL carry a `DISCOVERED` value meaning **the discovery walk found this resource, the
membership's policy admitted it, and no upload has been attempted for it**. It SHALL be recorded for
every resource a cycle's walk admitted and the engine judged to be new work, **before** any upload
job is created for that cycle.

The ledger SHALL be the upload cycle's **source of work**: a producer SHALL enqueue from the ledger's
rows rather than from the walk's return value, so a cycle can make progress on work it already knows
about whatever the walk returns. Every walk is a full enumeration, but it re-reads resources only for assets
the ledger does not fully know (see "A walk re-reads only the assets the ledger does not fully know"), so a
row that needs a job is found by this read, never re-derived by the walk. The `LedgerStore` SHALL expose a
state-scoped read of the rows that need a job, and
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

A row this read returned whose key the platform resolves to **nothing** SHALL have that row, and only that
row, deleted (see "Deletion is a presence diff over an authoritative walk"). Its asset has left the library,
or, under a partial grant, left the selection. Either way it can no longer be uploaded, and a row that
still needs a job for it would be offered on every cycle. Deleting by key rather than by asset is what
leaves the asset's settled rows alone: this read selects rows by key.

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

- **WHEN** a cycle runs with rows in `DISCOVERED` or `FAILED` and its walk returns no asset it has not
  already recorded
- **THEN** it resolves those rows' keys and enqueues them, rather than treating a walk with nothing new as
  no work

#### Scenario: A FAILED row is re-enqueued without re-reading its asset

- **WHEN** a row rests `FAILED` and its asset is fully recorded, so the walk skips its resources
- **THEN** the next cycle re-enqueues it from the ledger, rather than waiting for the walk to re-derive it

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
- **THEN** that row is not surfaced as a lost transfer and is not written to `FAILED`

#### Scenario: The batch bounds the resolved work, not the read

- **WHEN** the rows needing a job exceed one cycle's batch and some of them are excluded by the
  membership's current policy
- **THEN** the cycle resolves at most one batch of **admitted** rows, and the excluded ones neither
  consume the batch nor prevent admitted rows from being enqueued

#### Scenario: An excluded row is retained, not pruned

- **WHEN** the membership's policy stops admitting an asset whose row needs a job
- **THEN** the row is left in the ledger untouched — no state change, no prune — so widening the policy
  again re-admits it and the next cycle enqueues it with no re-read of its asset

### Requirement: The needs-job set is decided in Kotlin

Which `LedgerState` values **need an upload job** SHALL be decided by a single exhaustive `when` in
`:domain` `model/`, and bound into the work-source read as a parameter — never written as a literal
inside a query. It is one of several independent classifications over the same state set: a state may
be neither done nor in need of a job (`REQUESTED`), and every state SHALL be classified on
**every** axis.

Each axis answers a different question, and no axis implies another:

- **done** — is anything still owed for this key?
- **needs a job** — is nothing in flight and are the bytes not on the backend?
- **bytes believed stored** — does this row assert that the upload landed? `COMPLETED` does. This is the axis
  a comparison against the backend's own listing takes (capability `upload-state-reconciliation`). On the
  OS-driven tier the returned upload job carries no HTTP status, so a `COMPLETED` recorded there is a belief
  the device cannot distinguish from a stored `502` — which is why this axis exists at all.

Today **done** and **bytes believed stored** classify every state identically. They SHALL nonetheless remain
separate decisions: they answer different questions, a future state may separate them, and a comparison
against the backend SHALL NOT depend on what "settled" means.

A state added without classifying it on **every** axis SHALL fail to compile, rather than landing
silently on one side of any of them. The axes are therefore open-ended by construction: adding one is
ordinary, and it is what stops a new state from being filed by a query's string comparison instead of by
a decision.

#### Scenario: A new state must be classified on every axis

- **WHEN** a value is added to `LedgerState` and any one of the classifications is not updated
- **THEN** the build fails, because each decision is an exhaustive `when` with no `else` branch

#### Scenario: The classifications are independent

- **WHEN** the classifications are applied to `REQUESTED` and `COMPLETED`
- **THEN** `REQUESTED` is neither done, nor in need of a job, nor believed stored, while `COMPLETED` is done
  and believed stored and needs no job — so a read of one set never implies another

### Requirement: The ledger records the destination a job was sent to

A ledger row SHALL carry the **destination path** the upload for that row was addressed to, recorded at the
moment the row is written as in-flight — the same write that records the request, carrying the request it
already holds. No second write, no new ordering, and therefore no window in which a job exists whose
destination the ledger does not know that did not already exist.

The column exists so a row is recoverable from **what the external system persisted**. The OS-driven upload
tier hands a destination request to the platform and the process dies; when the platform returns the
finished job, the destination is the only field reliably present — `resource` is nil for a succeeded job —
and the row must be found from it. Under the previous byte-route shape the ledger key happened to be the
destination's last path segment, so recovery was free; that was an accident of formatting, not a decision,
and it does not survive a route that names identity in its path.

The value SHALL be the URL's **path**, not the whole URL: the path is what the platform must preserve in
order to perform the request at all, while a query or header may be normalized by a store this system does
not control.

The column SHALL be **nullable**, and a row without it SHALL remain fully usable. Rows written by a build
that predates this column exist on every device that upgrades, and the recovery that reads them is defined
by the tier that owns it (capability `ios-photokit-upload`).

Recording a destination SHALL NOT make the ledger key event-dependent or expiry-dependent. The key remains
the bare, event-independent object name (see "Event-independent key"), and the destination is stable with
no expiry, so a row's recorded destination stays valid for as long as the row does.

#### Scenario: The destination is recorded with the in-flight write

- **WHEN** a row is recorded as requested for an upload that has just been created
- **THEN** the row carries the destination path that upload was addressed to, written by that same
  operation

#### Scenario: A row is recoverable by its destination

- **WHEN** a returned upload job carries a destination whose path matches a recorded row
- **THEN** that row is identified, including when the destination's last path segment is not the row's key

#### Scenario: A row written before the column is still usable

- **WHEN** a row predates this column and carries no destination path
- **THEN** the row reads and writes normally, and its recovery falls to the tier-specific fallback

#### Scenario: The key is unchanged

- **WHEN** a row carrying a destination path is read
- **THEN** its key is still the bare, event-independent object name, unchanged by the addition

### Requirement: Deletion is a presence diff over an authoritative walk

The upload cycle SHALL delete a ledger row because its asset left the library **only** when all of the
following hold:

1. **The walk is authoritative.** The cycle's discovery reported `fullEnumeration`: it read the library
   itself, under a full grant, and the read succeeded, so every asset inside the policy's capture window was
   returned. A partial grant's selection snapshot is not the library (capability `limited-photo-access`),
   and an unreadable library returns nothing, so neither is evidence of absence. A walk that is not
   authoritative SHALL delete nothing.
2. **The row is inside the walk's window.** The row's asset is admitted by the membership's policy through
   the same row-admission derivation the device manifest and the enqueue use (`admittedAssetIds`). The
   ledger is device-global and the walk is bounded by the policy's capture range, so a row outside that
   range is not evidence either way. A bare row (empty `creationDate`) is never admitted, so it is never
   deleted this way.
3. **The asset is absent from the walk.** Presence SHALL be the asset ids of **every** candidate the walk
   returned, before admission. Being in the library is not a question of scope.
4. **The row is not `REQUESTED`.** A live platform job owns a `REQUESTED` row until its terminal write lands,
   and that write matches only a `REQUESTED` row. The row is deleted by the first authoritative walk after it
   settles.

The cycle SHALL decide the deletion from the same walk that supplies presence, and SHALL apply it through
`deleteKeys` before it records that walk's discoveries and before it publishes the device manifest, so a
departed photo is never listed by the cycle that saw it leave.

A second path deletes one row at a time: a ledger key the cycle asked the platform to resolve for a job that
resolves to **nothing** SHALL have **that row** deleted, and no other (see "The DISCOVERED state and the
ledger as the upload work source"). It needs no authoritative gate, because it only ever reaches a row that
still needs a job, and deleting one costs a re-discovery, never a photo.

#### Scenario: A departed in-window asset's rows are deleted
- **WHEN** an authoritative walk does not return asset `X`, whose `COMPLETED` rows carry a capture date the
  policy admits
- **THEN** the cycle deletes those rows before recording its discoveries, and the manifest it publishes no
  longer lists `X`

#### Scenario: A row outside the walk's window is kept
- **WHEN** the ledger holds a `COMPLETED` row whose capture date is before the membership's cutoff (seeded by
  a re-join, or left by an earlier event), and an authoritative walk does not return its asset
- **THEN** the row is kept

#### Scenario: A bare row is never deleted by the walk
- **WHEN** the ledger holds a row with an empty `creationDate` and an authoritative walk does not return its
  asset
- **THEN** the row is kept

#### Scenario: A selection snapshot deletes nothing
- **WHEN** the cycle runs under a partial grant, the member has de-selected a photo whose `COMPLETED` row is
  in-window, and the scoped discovery does not return it
- **THEN** no row is deleted, because the discovery was not authoritative

#### Scenario: An unreadable walk deletes nothing
- **WHEN** the platform reports the library not readable and the discovery returns no candidates
- **THEN** no row is deleted

#### Scenario: An in-flight row outlives its asset until it settles
- **WHEN** an authoritative walk does not return asset `X`, one of whose rows is `REQUESTED`
- **THEN** that row is kept and `X`'s settled rows are deleted; once the job's terminal write lands, the next
  authoritative walk deletes the remaining row

#### Scenario: A resolve failure deletes only its own key
- **WHEN** a `DISCOVERED` row `X-live.mov` resolves to nothing while its sibling `X-primary.heic` is
  `COMPLETED`
- **THEN** `X-live.mov` is deleted and `X-primary.heic` is untouched

### Requirement: A walk re-reads only the assets the ledger does not fully know

The upload cycle SHALL read an admitted candidate's resources **only** when the ledger does not fully know
its asset. Every upload walk is a full enumeration, so reading every admitted candidate's resources would
repeat one synchronous platform round-trip per photo on every cycle, for photos already recorded. A
candidate's resources are read when the ledger holds no row for its asset, or when at least one
of its asset's rows is bare (its manifest detail is not yet filled). Every other admitted candidate SHALL be
skipped. An uploaded resource is immutable and the ledger keeps no content version, so re-reading it could
only answer "already uploaded". A row that still needs a job is picked up from the ledger, not from the walk.

The skip is sound only if an asset is never partly recorded. The cycle SHALL therefore record the new
`DISCOVERED` rows of one walk with **one** batch record write, `recordAllUnlessSettled(entries)`. That write
applies each entry under the same done-state guard as the single record write, in **one storage
transaction**, and signals `changes` once if any entry applied. A process death then leaves either all of a
walk's new rows or none of them, never one role of a photo whose other role is skipped forever.

A re-join seed produces bare rows, so an asset whose stored-file listing named only some of its roles is
read again, and its missing roles are discovered.

#### Scenario: A fully-known asset is not re-read
- **WHEN** an authoritative walk returns an admitted asset all of whose rows exist and carry manifest detail
- **THEN** the cycle does not read that asset's resources

#### Scenario: An unknown or bare asset is read
- **WHEN** an authoritative walk returns an admitted asset with no row, or with a bare row
- **THEN** the cycle reads its resources, records new work `DISCOVERED`, and fills the bare rows' detail

#### Scenario: A walk's discoveries land together or not at all
- **WHEN** a walk discovers a Live Photo's primary and paired-video resources, and the process dies during
  the batch record write
- **THEN** afterwards the ledger holds either both rows or neither, so the next walk either skips a fully
  recorded asset or reads it again

#### Scenario: A partial seed is completed by the walk
- **WHEN** a re-join seed recorded only `X-primary.heic` (bare), and the walk returns `X`
- **THEN** the cycle reads `X`'s resources, fills the primary row's detail, and records `X-live.mov`
  `DISCOVERED`

