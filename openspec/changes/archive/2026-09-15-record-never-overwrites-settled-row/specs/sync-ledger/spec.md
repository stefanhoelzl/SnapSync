## MODIFIED Requirements

### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerStore` interface with the row
read `get(key): LedgerEntry?`, the guarded record write `recordUnlessSettled(entry): Boolean` (see
"Record operations" — a single-row upsert that never overwrites a row in a done state, and answers whether
it applied), the guarded terminal write
`markTerminal(key, state): Boolean` (see "Guarded terminal write"), the guarded promotion
`promoteUploaded(key): Boolean` (see "Guarded promotion"), the state-scoped read of `UPLOADED` rows
(see "Uploaded-row read"), the state-scoped read of `REQUESTED` keys, the bounded state-scoped read of
rows that **need a job** (see "The DISCOVERED state and the ledger as the upload work source"), the
manifest projection read and its detail backfill, the aggregate read
`aggregates(): LedgerAggregates`, a change signal `changes: Flow<Unit>`, `clear()` — a
delete-all reset, `clearRequested()` — delete every `REQUESTED` row, `resetTo(entries)` — an **atomic**
delete-all-then-insert-all replacement, the asset-targeted bulk mark `markAbsent(assetId)` — mark
every row whose `assetId` equals the argument as absent, **keeping** the rows — its inverse
`markPresent(assetIds)` — clear the absence mark of every row whose `assetId` is among the arguments — and
the provenance sweep `backfillEventId(eventId)` (see "Event provenance and the backfill sweep").

There is deliberately **no** unconditional per-row upsert (`put`). It was removed when the record path became
guarded: with no production caller left, it could only serve as an unguarded door for the next production
write. Tests seed a store through the guarded record write or `resetTo`, exactly as production writes it.

Backends SHALL store the fields of an applied write verbatim (no interpretation, no clocks of their own). The
**only** precedence a backend applies is the one each named guarded operation states — the record write's
done-state guard, `markTerminal`'s `REQUESTED` guard, `promoteUploaded`'s `UPLOADED` guard — and each SHALL be
enforced inside the storage statement itself, never by a read followed by a write. The reset family SHALL
apply no precedence at all. A `LedgerEntry` SHALL carry `key`, `assetId`, `state` (`DISCOVERED` |
`REQUESTED` | `UPLOADED` | `COMPLETED` | `FAILED`), `attempt`, and `eventId` — the event that was
joined when the row was recorded. `clear()`, `clearRequested()`, `resetTo`, `markAbsent`, an applied record
write, an applied `markPresent`, an applied
`markTerminal` and an applied `promoteUploaded` SHALL each remove (and, for `resetTo`, then insert) or
update the matching rows and signal `changes` **once** (so watchers re-read the
now-current truth).
`clear()`, `clearRequested()`, `resetTo`, `markAbsent`, and `markPresent` are **reset/bulk** operations, not the
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

#### Scenario: There is no delete-by-asset
- **WHEN** an asset leaves the device's library
- **THEN** its rows are marked absent and retained, and no seam operation exists that deletes rows by
  `assetId`

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

### Requirement: Record operations
`LedgerWriter` SHALL provide `recordDiscovered`, `recordRequested`, `recordCompleted`, and
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

#### Scenario: A settled row survives every record operation
- **WHEN** `recordRequested`, `recordFailed` or `recordCompleted` is called — with any attempt and eventId — for a
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

### Requirement: SQLDelight backend

A SQLDelight-backed `LedgerStore` SHALL be provided in `:adapter:generic:app` commonMain (SQLDelight
package `app.snapsync.engine.db`; moved from `:domain:engine` at migration step 4, whose module
died at step 10) with the schema
`key TEXT PRIMARY KEY, assetId TEXT NOT NULL, state TEXT NOT NULL, attempt INTEGER NOT NULL,
eventId TEXT NOT NULL DEFAULT '', absent INTEGER NOT NULL DEFAULT 0`
plus an index on `assetId` (backing `markAbsent`, `markPresent` and the `assetId`-grouped aggregate). The `absent`
column records that an asset has left the library; its `DEFAULT 0` SHALL be present in **both** the
migration and the CREATE statement, like `eventId`'s, and it is the correct resting value for a row
written before the column existed. Reads that answer *what does this device hold or share* SHALL
exclude marked rows; `get` SHALL NOT, so upload suppression survives a deletion. `state`
SHALL be a SQLDelight typed column (`AS LedgerState` via the built-in enum adapter); adapter wiring
SHALL be hidden in a single factory function so construction sites never see it. The schema carries
no timestamp column. The `eventId` column's `DEFAULT ''` SHALL be present in **both** the migration
and the CREATE statement (the SQLDelight migration-verify task proves the two schemas identical),
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
  subsequent record carrying a real `eventId` for a new key round-trips

#### Scenario: Fresh database is created at the current schema
- **WHEN** a database is created from scratch
- **THEN** it has the `assetId` index, no `updatedAt` column, an `eventId` column defaulting to
  `''`, and needs no migration step

#### Scenario: Adding absent preserves the rows unmarked
- **WHEN** a v6 database holding a `COMPLETED` row is migrated to the current schema
- **THEN** the row survives with its `key`, `assetId`, `state`, `eventId` and manifest detail intact and
  its `absent` unset, so it still suppresses re-upload and still projects into the manifest

### Requirement: Prune operations are writer-only

The asset-keyed bulk mark (`markAbsent`) and its inverse (`markPresent`) SHALL be exposed on
`LedgerWriter` (delegating to the backend) and SHALL NOT be exposed on any other app-facing ledger
surface. Each is a sync write by the single ledger writer, not the app-side `clear()` reset, and at
the writer layer it consults no engine state first. Because only the engine's
composition root constructs a `LedgerWriter`, mark access is confined to the single-writer process,
preserving the single-writer invariant.

`markPresent(assetIds)` SHALL clear the absence mark of every row whose `assetId` is among the arguments,
**whatever the row's state** — a settled row is exactly the one no record write will ever reach again. It
SHALL leave every other field untouched, and SHALL write nothing when no supplied asset has a marked row: the
common case, since it runs every cycle over every asset the walk saw. It SHALL signal `changes` only when it
cleared a mark.

The upload cycle SHALL call `markPresent` with the asset ids of **every** candidate its walk returned — before
the selection policy's admission, because being in the library is not a question of scope — and SHALL do so
after applying the change feed's removals for the same walk, so an asset named by both is left present.

#### Scenario: Writer marks an asset absent

- **WHEN** a `LedgerWriter` records a row for assetId `X` (key `X-photo.jpg`) and then calls
  `markAbsent("X")`
- **THEN** `entry("X-photo.jpg")` returns a row whose `absent` is set

#### Scenario: Writer marks a settled asset present again

- **WHEN** assetId `X` has a `COMPLETED` row marked absent, and the writer calls `markPresent({"X", "Y"})`
- **THEN** `entry("X-photo.jpg")` is still `COMPLETED` with every other field unchanged and `absent` unset, and
  `changes` signals once

#### Scenario: Marking present an asset that is not absent writes nothing

- **WHEN** `markPresent` is called with asset ids none of whose rows is marked absent
- **THEN** no row changes and `changes` does not signal

#### Scenario: The mark is absent from the non-writer surface

- **WHEN** a component holds the ledger only as a `LedgerStore` reader (no writer)
- **THEN** `markAbsent` and `markPresent` are not part of its sanctioned surface — they reach the backend only
  through the root-constructed `LedgerWriter`

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

Deletion from the library SHALL be recorded by the **precise** signal — the asset identifiers the
platform change feed reports removed — and SHALL mark the rows absent rather than removing them. There
SHALL be no full-enumeration retain-live reconcile: a deletion the change feed missed leaves a row
listed, whose bytes are still on the backend, so a member still downloads it successfully. The photo
remains in the event, which is what already happens when a member leaves. Exhaustive deletion-tracking
is therefore not required. An asset the walk sees in the library again SHALL have its rows un-marked (see
"Prune operations are writer-only").

#### Scenario: A narrowing scope removes no rows
- **WHEN** the membership's capture cutoff is raised and a fully-drained full enumeration then runs
- **THEN** every ledger row is retained, including those for assets now outside the range

#### Scenario: Turning the direction off removes no rows
- **WHEN** a contributing membership's direction is turned off and a cycle runs
- **THEN** the event's ledger rows are retained in full, so re-enabling the direction re-uploads nothing

#### Scenario: A deletion reported by the change feed marks the rows
- **WHEN** the platform change feed reports an asset removed
- **THEN** that asset's rows are marked absent and remain readable, so the next manifest projection stops
  listing it while re-upload stays suppressed

#### Scenario: Narrow then widen re-lists without re-uploading
- **WHEN** a member narrows their scope, a full enumeration runs, and the member then widens it back
- **THEN** the previously-uploaded assets are listed again and no byte is re-uploaded

#### Scenario: A restored asset does not re-upload
- **WHEN** an asset marked absent is restored to the library and discovered again
- **THEN** its `COMPLETED` row still suppresses re-upload of the same key, and its absence mark is cleared

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
