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
## Requirements
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

### Requirement: Aggregate reads
`LedgerStore.aggregates()` SHALL answer `LedgerAggregates(pending, completed)` computed in one
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

#### Scenario: Writer reads what it wrote

- **WHEN** a `LedgerWriter` records an entry and `entry(key)` is called on the same instance
- **THEN** the recorded entry is returned

#### Scenario: Record access exists only where the writer is constructed

- **WHEN** a component is composed without receiving the root's `LedgerWriter`
- **THEN** it has no record operation available — it can read the ledger only through
  `LedgerStore`'s read operations

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

`LedgerStore` SHALL expose a read of the non-`COMPLETED` rows as `(assetId, key)` pairs (the
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
