## MODIFIED Requirements

### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerStore` interface with the row
operations `get(key): LedgerEntry?` and `put(entry)` (a single-row upsert), the aggregate read
`aggregates(): LedgerAggregates`, a change signal `changes: Flow<Unit>`, `clear()` — a
delete-all reset, `resetTo(entries)` — an **atomic** delete-all-then-insert-all replacement, the
asset-targeted bulk mark `markAbsent(assetId)` — set the **absent** flag on every row whose `assetId`
equals the argument, without deleting it — and the provenance sweep `backfillEventId(eventId)` (see
"Event provenance and the backfill sweep").
Backends SHALL store entries verbatim (no interpretation, no precedence logic, last write wins, no
clocks of their own). A `LedgerEntry` SHALL carry `key`, `assetId`, `state` (`REQUESTED` |
`COMPLETED` | `FAILED`), `attempt`, `eventId` — the event that was joined when the row was
recorded — and `absent`, whether the asset has since left the device's library. `clear()`, `resetTo`,
and `markAbsent` SHALL each remove, insert, or update the matching
rows and signal `changes` **once** like a `put` (so watchers re-read the now-current truth).
`clear()`, `resetTo`, and `markAbsent` are **reset/bulk** operations, not the
per-key **record** operations; recording per-upload facts remains the single-record-writer's job
(`LedgerWriter`), so a non-writer holder of the backend may reset the store without breaching the
single-record-writer invariant. `assetId` is a second opaque field: the backend stores, groups, and
matches it by equality but never interprets it (it does not know what an "asset" means — any value is
valid, set by the caller), so the ledger remains a dumb, platform-neutral row store. `eventId` is a
third opaque field with the same posture: the backend stores it verbatim and matches it by equality
only where an operation's contract says so (the backfill's sentinel match); it does not know what an
"event" means. `absent` is a stored flag the backend also never interprets: it sets it on `markAbsent`
and returns it verbatim, and no backend operation filters on it.

There SHALL be **no** operation that deletes rows by asset. A row records that a resource's bytes are
on the backend, and nothing on the device can make that false — no local action deletes an uploaded
object (capability `scheduled-cleanup` owns the only deletion, and it deletes whole events). Absence
from the library is therefore recorded, not enacted by removal.

#### Scenario: Put then get round-trips
- **WHEN** `put(entry)` is called and then `get(entry.key)`
- **THEN** the returned entry equals the one put, field for field — including `assetId`, `eventId`, and
  `absent`

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

#### Scenario: Mark absent flags only that asset's rows and signals
- **WHEN** the store holds rows for assetId `A` (keys `A-photo.jpg`, `A-video.mov`) and assetId `B`
  (key `B-photo.jpg`), and `markAbsent("A")` is called
- **THEN** `get("A-photo.jpg")` and `get("A-video.mov")` return rows whose `absent` is set and whose
  other fields are unchanged, `get("B-photo.jpg")` is unchanged, and a `changes` signal is emitted

#### Scenario: Marking absent is idempotent
- **WHEN** `markAbsent` is called twice for the same assetId
- **THEN** the rows are unchanged after the second call and remain readable

#### Scenario: An absent row keeps its upload state
- **WHEN** a `COMPLETED` row is marked absent
- **THEN** `get` still returns it with `state = COMPLETED`, so it continues to suppress re-upload of the
  same key

#### Scenario: eventId is stored verbatim including the sentinel
- **WHEN** one entry is put with a real `eventId` and another with the pre-provenance sentinel `""`
- **THEN** `get` returns each `eventId` exactly as supplied — the backend neither fills nor
  interprets the sentinel on a row operation

### Requirement: Prune operations are writer-only

The asset-keyed bulk mark (`markAbsent`) SHALL be exposed on
`LedgerWriter` (delegating to the backend) and SHALL NOT be exposed on any other app-facing ledger
surface. It is a sync write by the single ledger writer, not the app-side `clear()` reset, and at
the writer layer it consults no engine state first. Because only the engine's
composition root constructs a `LedgerWriter`, mark access is confined to the single-writer process,
preserving the single-writer invariant.

#### Scenario: Writer marks an asset absent

- **WHEN** a `LedgerWriter` records a row for assetId `X` (key `X-photo.jpg`) and then calls
  `markAbsent("X")`
- **THEN** `entry("X-photo.jpg")` returns a row whose `absent` is set

#### Scenario: The mark is absent from the non-writer surface

- **WHEN** a component holds the ledger only as a `LedgerStore` reader (no writer)
- **THEN** `markAbsent` is not part of its sanctioned surface — it reaches the backend only through the
  root-constructed `LedgerWriter`

### Requirement: SQLDelight backend

A SQLDelight-backed `LedgerStore` SHALL be provided in `:adapter:generic:app` commonMain (SQLDelight
package `app.snapsync.engine.db`; moved from `:domain:engine` at migration step 4, whose module
died at step 10) with the schema
`key TEXT PRIMARY KEY, assetId TEXT NOT NULL, state TEXT NOT NULL, attempt INTEGER NOT NULL,
eventId TEXT NOT NULL DEFAULT '', absent INTEGER NOT NULL DEFAULT 0`
plus an index on `assetId` (backing `markAbsent` and the `assetId`-grouped aggregate). The `absent`
column records that an asset has left the library; its `DEFAULT 0` SHALL be present in **both** the
migration and the CREATE statement, like `eventId`'s, and it is the correct resting value for a row
written before the column existed. Reads that answer *what does this device hold or share* SHALL
exclude marked rows; `get` SHALL NOT, so upload suppression survives a deletion. `state`
SHALL be a SQLDelight typed column (`AS LedgerState` via the built-in enum adapter); adapter wiring
SHALL be hidden in a single factory function so construction sites never see it. The schema carries
no timestamp column. The `eventId` column's `DEFAULT ''` SHALL be present in **both** the migration
and the CREATE statement (the SQLDelight migration-verify task proves the two schemas identical),
and SHALL NOT be removed while any shipped build may write a 4-column row (see "Event provenance
and the backfill sweep", staged revert). `put` SHALL be a single SQL upsert statement;
`aggregates()` SHALL be a single
SQL round-trip (an `assetId`-grouped query). Every `LedgerStore` implementation SHALL satisfy the
shared `LedgerStoreContract` (hosted in `:test:world` commonMain since step 10): the JVM/sqlite and
native (simulator) driver tests extend it from `:adapter:generic:app`'s test source sets, and
`:adapter:generic:fake`'s honest `InMemoryLedgerStore` — the store the world harness runs on — extends it
from `:test:world`'s own tests. The native (iOS) driver is wired by `:adapter:ios:ext-safe`'s
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

### Requirement: Requested-state reset

`LedgerStore` SHALL provide `clearRequested()`: a bulk delete of **every row whose state is
`REQUESTED`**, leaving `COMPLETED` and `FAILED` rows untouched. It SHALL emit exactly one `changes`
signal on success (like `clear`/`resetTo`). On the SQLDelight backend it SHALL be a single indexed-by
-state `DELETE … WHERE state = 'REQUESTED'`.

`clearRequested` is an **app-side reset-family** operation — in the same family as `clear()` and
`resetTo()`, **not** the writer-only mark (`markAbsent`). It SHALL be
callable on the `LedgerStore` **without** a `LedgerWriter`, so a non-writer holder of the backend may
invoke it without breaching the **single-record-writer invariant** (exactly one holder records per-key
upload facts; *which process* holds that writer is a platform binding, not a ledger concern).

`clearRequested` is a **blanket** recovery for stranded `REQUESTED` rows on a platform that **cannot
enumerate its in-flight jobs**: those resources remain `REQUESTED` in the ledger, the engine never
re-issues a `REQUESTED` key, and with no way to detect which are genuinely in flight a bulk `REQUESTED`
clear is the only way to let the next discovery re-create them. Its canonical use is the iOS ≥26.1
PhotoKit tier, where disabling the extension wipes **all** in-flight OS jobs at once (so no
genuinely-in-flight row is lost by clearing all `REQUESTED`) — see `ios-photokit-upload`. A platform
whose upload queue **is** enumerable (e.g. the iOS 18–26.0 background-`URLSession` tier, which can list
its live tasks) MAY instead reconcile stranded rows **precisely** and need not use this blanket clear;
`clearRequested` remains available but is not required on such a platform.

#### Scenario: clearRequested removes only REQUESTED rows

- **WHEN** the store holds a `REQUESTED` row, a `COMPLETED` row, and a `FAILED` row, and
  `clearRequested()` is called
- **THEN** the `REQUESTED` row is gone and the `COMPLETED` and `FAILED` rows are unchanged

#### Scenario: clearRequested emits one change signal

- **WHEN** `clearRequested()` succeeds over a store containing at least one `REQUESTED` row
- **THEN** exactly one `changes` signal is emitted, so a watcher re-reads the now-cleared truth

#### Scenario: A re-created key uploads again after a clear

- **WHEN** a key is `REQUESTED`, `clearRequested()` drops it, and the next discovery re-derives that
  key (`ResourceChanged`)
- **THEN** the engine answers `Work` (the key is now absent), not `AlreadyUploaded`

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
  subsequent `put` carrying a real `eventId` round-trips

#### Scenario: Fresh database is created at the current schema
- **WHEN** a database is created from scratch
- **THEN** it has the `assetId` index, no `updatedAt` column, an `eventId` column defaulting to
  `''`, and needs no migration step

#### Scenario: Adding absent preserves the rows unmarked
- **WHEN** a v6 database holding a `COMPLETED` row is migrated to the current schema
- **THEN** the row survives with its `key`, `assetId`, `state`, `eventId` and manifest detail intact and
  its `absent` unset, so it still suppresses re-upload and still projects into the manifest

## ADDED Requirements

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
is therefore not required.

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
- **THEN** its `COMPLETED` row still suppresses re-upload of the same key

## REMOVED Requirements

### Requirement: Prune operations hold on the SQLDelight backend

**Reason**: `retainAssets` is removed from the seam entirely — the ledger is never pruned by policy, and
deletion is recorded by marking rather than by removal (see *The ledger is never pruned by the selection
policy*). The requirement's substance was the bind-variable-limit avoidance for `retainAssets`'
complement delete, which no longer exists: `markAbsent` is an indexed `UPDATE … WHERE assetId = ?` over
one asset, so no unbounded parameter list arises.

**Migration**: Backends drop `retainAssets` and implement `markAbsent(assetId)` as an indexed `UPDATE`
setting the `absent` column. The mark's storage-seam scenarios in *Storage seam — dumb row store* SHALL
pass against the SQLDelight backend on the JVM sqlite driver via the shared backend contract, as the
prune scenarios previously did. A ledger schema migration adds the `absent` column, defaulting to unset
for existing rows.
