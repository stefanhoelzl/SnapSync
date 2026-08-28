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

## Requirements

### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerStore` interface with the row
operations `get(key): LedgerEntry?` and `put(entry)` (a single-row upsert), the guarded terminal write
`markTerminal(key, state): Boolean` (see "Guarded terminal write"), the guarded promotion
`promoteUploaded(key): Boolean` (see "Guarded promotion"), the state-scoped read of `UPLOADED` rows
(see "Uploaded-row read"), the state-scoped read of `REQUESTED` keys, the bounded state-scoped read of
rows that **need a job** (see "The DISCOVERED state and the ledger as the upload work source"), the
manifest projection read and its detail backfill, the aggregate read
`aggregates(): LedgerAggregates`, a change signal `changes: Flow<Unit>`, `clear()` — a
delete-all reset, `clearRequested()` — delete every `REQUESTED` row, `resetTo(entries)` — an **atomic**
delete-all-then-insert-all replacement, the asset-targeted bulk mark `markAbsent(assetId)` — mark
every row whose `assetId` equals the argument as absent, **keeping** the rows — and the provenance
sweep `backfillEventId(eventId)` (see "Event provenance and the backfill sweep").
Backends SHALL store entries verbatim (no interpretation, no precedence logic, last write wins, no
clocks of their own). A `LedgerEntry` SHALL carry `key`, `assetId`, `state` (`DISCOVERED` |
`REQUESTED` | `UPLOADED` | `COMPLETED` | `FAILED`), `attempt`, and `eventId` — the event that was
joined when the row was recorded. `clear()`, `clearRequested()`, `resetTo`, `markAbsent`, an applied
`markTerminal` and an applied `promoteUploaded` SHALL each remove (and, for `resetTo`, then insert) or
update the matching rows and signal `changes` **once** like a `put` (so watchers re-read the
now-current truth).
`clear()`, `clearRequested()`, `resetTo`, and `markAbsent` are **reset/bulk** operations, not the
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

#### Scenario: Put then get round-trips
- **WHEN** `put(entry)` is called and then `get(entry.key)`
- **THEN** the returned entry equals the one put, field for field — including `assetId` and `eventId`

#### Scenario: A guarded terminal write signals like a put
- **WHEN** `markTerminal` applies to a row
- **THEN** `changes` signals exactly once, as it would for a `put`

#### Scenario: There is no delete-by-asset
- **WHEN** an asset leaves the device's library
- **THEN** its rows are marked absent and retained, and no seam operation exists that deletes rows by
  `assetId`

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

#### Scenario: An uploaded-but-unpromoted photo counts pending
- **WHEN** one asset has one `COMPLETED` row and one `UPLOADED` row
- **THEN** `aggregates()` answers `pending = 1, completed = 0`

### Requirement: Change signal

`LedgerStore.changes` SHALL emit `Unit` after every successful `put`. A ding carries no payload and
promises nothing beyond "re-read the truth" — consumers MUST treat it as a level trigger (conflation,
duplicate dings, and signals missed while busy are all safe because every re-read queries current state).
The signal is **in-process only**: the ledger is the extension's private upload memory and has no
cross-process watcher, so the backend SHALL NOT post any cross-process (Darwin) notification, and there is
no app-process observer to merge. The seam itself does not change.

#### Scenario: Put dings

- **WHEN** a collector is active on `changes` and `put` completes
- **THEN** the collector receives an emission

#### Scenario: No cross-process notification is posted

- **WHEN** the extension process performs `put`s within a `process()` cycle
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
operation: `markTerminal` (see "Guarded terminal write") is reachable through `LedgerStore`, because the
party the platform tells that an upload terminated is a platform callback inside the record-writing process,
and it cannot suspend. The invariant holds — that callback belongs to the one recording process — while the
type-level codification does not cover it. A spec or a review that reads the type-level rule as the
invariant will reach the wrong conclusion about this call, which is why both are stated.

No operation other than `markTerminal` SHALL be added to `LedgerStore` on this argument; a further record
operation belongs on the writer.

#### Scenario: Writer reads what it wrote

- **WHEN** a `LedgerWriter` records an entry and `entry(key)` is called on the same instance
- **THEN** the recorded entry is returned

#### Scenario: Record access exists only where the writer is constructed

- **WHEN** a component is composed without receiving the root's `LedgerWriter`
- **THEN** it has no record operation available beyond `markTerminal` — it can otherwise read the ledger
  only through `LedgerStore`'s read operations

#### Scenario: One process records

- **WHEN** the platform callback records a terminal upload through `LedgerStore` and the cycle records
  through the `LedgerWriter`
- **THEN** both are inside the single record-writing process for that tier, and no second process records

### Requirement: Record operations
`LedgerWriter` SHALL provide `recordDiscovered`, `recordRequested`, `recordCompleted`, and
`recordFailed`. Each SHALL
upsert a complete, self-contained entry for the key (assetId, state, attempt, eventId as supplied
by the caller) — no operation depends on a prior read, and each maps to a single backend `put`.
`assetId` and `eventId` are supplied positionally as `recordX(key, assetId, attempt, eventId)` (the
writer stays on primitives, decoupled from the engine's `Resource`; the eventId is per-call because
the writer outlives any one membership — it is constructed at composition time, while the joined
event arrives per cycle with the gate). `recordDiscovered` additionally carries the resource's
manifest detail, because the walk is the only reader of a capture date and a row recorded without one
is excluded from every projection until a later walk backfills it. The writer records no timestamp and reads
no clock — the engine, writer, and backends are all clock-free. Duplicate record operations with
identical arguments SHALL converge on assetId, state, attempt, and eventId.

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

#### Scenario: An uploaded row is outstanding

- **WHEN** a row is `UPLOADED`
- **THEN** the pending-resource read returns it

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

#### Scenario: A completed row names its resource fully

- **WHEN** a resource upload completes and its ledger row is COMPLETED
- **THEN** the row carries `creationDate`, `role`, `contentType`, and `filename` sufficient to build the
  resource's device-manifest entry with no additional PhotoKit read

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

### Requirement: Lifecycle transitions never clear the ledger

`clear()` SHALL NOT be used as a membership-lifecycle mechanism. No provision, re-provision, event
switch, permission change, direction change, or **leave** SHALL call `clear()` on the ledger
(`upload-lifecycle`, "Upload producer seam has no destructive verb").

The ledger is **device-global dedup state**, not event state: its key is the bare resource filename
with no event scoping (see "Event-independent key"), and leaving an event does not remove the device's
bytes from its storage partition. A `COMPLETED` row therefore stays **true** across a leave, a switch,
and a re-join — and clearing it would force a re-upload of every already-stored resource on the next
join.

The discovery cursor is **not** part of this prohibition, because it is not dedup state. It records where
an incremental scan resumes; a tier may clear it to repair its own mechanism (`upload-lifecycle`), and a
reconciliation clears it whenever it re-baselines (`event-rejoin-reconciliation`). What that costs is a
full re-enumeration whose every resource is already `COMPLETED` here — which is precisely why the ledger
is the thing that must not be cleared, and the cursor is not.

The **only** operation that re-baselines the ledger SHALL be `resetTo`, invoked by a triggered
reconciliation against the authoritative per-device listing (`event-rejoin-reconciliation`). Ledger and
storage may diverge only at a (re)join, and reconciliation — not a lifecycle wipe — is what closes that
divergence.

`clear()` SHALL remain on the `LedgerStore` seam (it is the semantic basis of `resetTo` and is used
by test and harness backends), but it SHALL have no membership-lifecycle caller.

#### Scenario: Leaving an event preserves every ledger row

- **WHEN** the user leaves the currently-joined event
- **THEN** the ledger retains every row, so joining any event afterwards re-uploads nothing already in the device's byte partition — whether or not the tier's `stop()` cleared its discovery cursor

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

### Requirement: The UPLOADED state and its promotion

`LedgerState` SHALL carry a fourth value, `UPLOADED`: **the resource's bytes are durably stored, and the
work that a completion triggers has not yet run.** It is written by whichever party the platform tells that
the upload terminated, at the moment it is told; it is promoted to `COMPLETED` by the upload cycle once that
work has run.

`UPLOADED` SHALL be a **non-done** state (see "The done-state set is decided in Kotlin"): it counts toward
the backlog in every read, and the device manifest — which projects `COMPLETED` rows — SHALL NOT include it.
Only the cycle's promotion pass treats it as outstanding work rather than as pending upload.

The engine's per-key decision SHALL treat `UPLOADED` as **already uploaded** (skip), like `COMPLETED` and
`REQUESTED`: its bytes are stored, so re-uploading them would be waste.

Adding this value SHALL require **no schema migration**: `state` is stored as text mapped to the enum, so a
database written by an earlier build simply contains no rows in the new state.

#### Scenario: A terminal upload is recorded before any cycle runs

- **WHEN** the platform reports that an upload for a `REQUESTED` key succeeded
- **THEN** that row's state becomes `UPLOADED`, and it remains `UPLOADED` across process death until a cycle
  promotes it

#### Scenario: An UPLOADED row is not re-uploaded

- **WHEN** discovery re-derives a resource whose row is `UPLOADED`
- **THEN** the engine answers already-uploaded and creates no upload job

#### Scenario: An UPLOADED row counts as outstanding

- **WHEN** an asset has one `UPLOADED` row and no other rows
- **THEN** `aggregates()` counts that asset as pending, the pending-resource read returns its key, and the
  device-manifest projection excludes it

#### Scenario: An older database needs no migration

- **WHEN** a build carrying `UPLOADED` opens a ledger written by a build that predates it
- **THEN** the schema is unchanged, every existing row decodes, and no migration step runs

### Requirement: The done-state set is decided in Kotlin

Which `LedgerState` values count as **done** SHALL be decided by a single exhaustive `when` in `:domain`
`model/`, and bound into every state-scoped storage read as a parameter — never written as a literal inside a
query. On the SQLDelight backend the pending-resource read, the aggregate read, and the manifest projection
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

### Requirement: Guarded terminal write

`LedgerStore` SHALL expose `markTerminal(key, state): Boolean` — a **single guarded statement** that sets a
row's state **only while that row is still `REQUESTED`**, and answers whether it applied. On the SQLDelight
backend it SHALL be one `UPDATE … WHERE key = :key AND state = 'REQUESTED'` whose applied/not-applied answer
is read inside that statement's own transaction.

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

- **WHEN** `markTerminal(key, UPLOADED)` is called for a row whose state is `REQUESTED`
- **THEN** the row becomes `UPLOADED`, every other column is unchanged, and the call answers that it applied

#### Scenario: A row that moved on is not clobbered

- **WHEN** `markTerminal(key, FAILED)` is called for a row whose state is no longer `REQUESTED`
- **THEN** no row is changed and the call answers that it did not apply

#### Scenario: An absent row is reported, not assumed

- **WHEN** `markTerminal` is called for a key with no row
- **THEN** no row is created and the call answers that it did not apply

### Requirement: Guarded promotion

`LedgerStore` SHALL expose a promotion that sets a row `COMPLETED` **only while that row is still
`UPLOADED`**, and answers whether it applied. On the SQLDelight backend it SHALL be one guarded `UPDATE`
of the state column alone.

Updating one column rather than re-stating the row is required, not stylistic: a row carries provenance, an
attempt, the manifest detail and whether its asset has left the library, and a caller that re-stated them
would drop whichever column it had not been taught about — at the exact moment the row becomes eligible for
the device-manifest projection.

#### Scenario: An UPLOADED row is promoted with every other column intact

- **WHEN** a row that carries manifest detail, provenance and an attempt is promoted
- **THEN** its state becomes `COMPLETED`, every other column is unchanged, and the call answers that it
  applied

#### Scenario: A row that is not UPLOADED is not promoted

- **WHEN** promotion is called for a row in any other state, or for an absent key
- **THEN** no row is changed and the call answers that it did not apply

### Requirement: Uploaded-row read

`LedgerStore` SHALL expose a read of the rows whose state is `UPLOADED`, returning whole entries — so the
promotion pass has each row's `assetId` for album placement and its manifest detail for the promoting write.
The read SHALL return exactly those rows and interpret nothing else.

#### Scenario: Returns only uploaded rows

- **WHEN** the store holds a `REQUESTED`, an `UPLOADED`, a `FAILED` and a `COMPLETED` row and the
  uploaded-row read is called
- **THEN** it returns only the `UPLOADED` row, as a whole entry

#### Scenario: Survives the process that wrote it

- **WHEN** a row is marked `UPLOADED`, the process ends, and a new process reads the store
- **THEN** the uploaded-row read returns that row
### Requirement: The DISCOVERED state and the ledger as the upload work source

`LedgerState` SHALL carry a `DISCOVERED` value meaning **the discovery walk found this resource, the
membership's policy admitted it, and no upload has been attempted for it**. It SHALL be recorded for
every resource a cycle's walk admitted and the engine judged to be new work, **before** any upload
job is created for that cycle.

The ledger SHALL be the upload cycle's **source of work**: a producer SHALL enqueue from the ledger's
rows rather than from the walk's return value, so a cycle can make progress on work it already knows
about whatever the change feed reports. (A cycle still consults that feed — there is no cheaper way to
learn what the library did — but it no longer depends on the feed re-deriving work it has already
seen.) The `LedgerStore` SHALL expose a bounded state-scoped read of the rows that need a job, and
`DISCOVERED` and `FAILED` rows SHALL both be returned by it — they are the same fact to a producer,
differing only in whether an attempt has already been made.

`DISCOVERED` SHALL NOT be a done state, so a row in it counts toward the backlog everywhere and is
excluded from the device-manifest projection. It SHALL NOT be a stranding candidate: the stranded
reconciliation reads `REQUESTED` keys only, and surfacing a row that never had a job as a lost
transfer would record a failure that did not happen.

#### Scenario: A discovered resource is recorded before any job exists

- **WHEN** a cycle's walk admits a resource the engine judges to be new work
- **THEN** a `DISCOVERED` row is recorded for that resource's key before `createJob` is called for it

#### Scenario: A top-up enqueues from the ledger, not from the walk's output

- **WHEN** a cycle runs with rows in `DISCOVERED` or `FAILED` and its change feed reports nothing new
- **THEN** it resolves those rows' keys and enqueues them, rather than treating an empty change set as
  no work

#### Scenario: A FAILED row is re-enqueued without a full enumeration

- **WHEN** a row rests `FAILED` and its asset has not changed since the persisted discovery cursor
- **THEN** the next cycle re-enqueues it from the ledger, rather than waiting for a full enumeration
  to re-derive it

#### Scenario: A discovered row is backlog, not manifest

- **WHEN** a row is `DISCOVERED`
- **THEN** it counts toward the pending aggregate and the pending-resource read, and it appears in no
  device-manifest projection

#### Scenario: A discovered row is never stranded

- **WHEN** the stranded reconciliation runs while a `DISCOVERED` row exists with no live transfer
- **THEN** that row is not surfaced as a lost transfer and is not written to `FAILED`

### Requirement: The needs-job set is decided in Kotlin

Which `LedgerState` values **need an upload job** SHALL be decided by a single exhaustive `when` in
`:domain` `model/`, and bound into the work-source read as a parameter — never written as a literal
inside a query. It is a second, independent classification alongside the done-state set: a state may
be neither done nor in need of a job (`REQUESTED`, `UPLOADED`), and every state SHALL be classified on
both axes.

A state added without classifying it on **both** axes SHALL fail to compile, rather than landing
silently on one side of either.

#### Scenario: A new state must be classified on both axes

- **WHEN** a value is added to `LedgerState` and either the done-state decision or the needs-job
  decision is not updated
- **THEN** the build fails, because each decision is an exhaustive `when` with no `else` branch

#### Scenario: The two classifications are independent

- **WHEN** the classifications are applied to `REQUESTED` and `UPLOADED`
- **THEN** neither is done and neither needs a job, so a read of one set never implies the other

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
