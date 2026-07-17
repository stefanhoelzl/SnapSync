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
`changes/archive/2026-07-12-fix-app-driven-upload-lifecycle`.
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

### Requirement: Change signal

`LedgerBackend.changes` SHALL emit `Unit` after every successful `put`. A ding carries no payload and
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
this shared class, delegating storage to the injected `LedgerBackend`. There SHALL be no separate
reader type: the writer is constructed only by the composition root that owns the engine (one per
platform), and components that must not record are simply never handed a writer — app-side read
access goes through `LedgerBackend`'s read operations (`aggregates()`, per `sync-status`), never
through a writer instance.

#### Scenario: Writer reads what it wrote

- **WHEN** a `LedgerWriter` records an entry and `entry(key)` is called on the same instance
- **THEN** the recorded entry is returned

#### Scenario: Record access exists only where the writer is constructed

- **WHEN** a component is composed without receiving the root's `LedgerWriter`
- **THEN** it has no record operation available — it can read the ledger only through
  `LedgerBackend`'s read operations

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

### Requirement: Prune operations are writer-only

The two asset-keyed bulk removals (`deleteByAssetId`, `retainAssets`) SHALL be exposed on
`LedgerWriter` (delegating to the backend) and SHALL NOT be exposed on any other app-facing ledger
surface. They are sync writes by the single ledger writer, not the app-side `clear()` reset, and at
the writer layer they consult no engine state first (a backend may read its own rows to compute a
complement — an implementation detail, not part of the seam contract). Because only the engine's
composition root constructs a `LedgerWriter`, prune access is confined to the single-writer process,
preserving the single-writer invariant.

#### Scenario: Writer prunes by assetId

- **WHEN** a `LedgerWriter` records a row for assetId `X` (key `X-photo.jpg`) and then calls
  `deleteByAssetId("X")`
- **THEN** `entry("X-photo.jpg")` returns null

#### Scenario: Writer retains an asset set

- **WHEN** a `LedgerWriter` holds rows for assetIds `X` and `Y` and calls `retainAssets({"X"})`
- **THEN** the `Y` rows return null and the `X` rows are unchanged

#### Scenario: Prune is absent from the non-writer surface

- **WHEN** a component holds the ledger only as a `LedgerBackend` reader (no writer)
- **THEN** neither `deleteByAssetId` nor `retainAssets` is part of its sanctioned surface — prune
  reaches the backend only through the root-constructed `LedgerWriter`

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
ledger neither records nor consults an event when keying, recording, or reading a row. This is what
lets cross-event dedup come purely from the reconcile seed source (a `resetTo` clear-and-seed from the
device-global per-device listing) without any ledger key change.

#### Scenario: A COMPLETED row stays valid after the configured event changes
- **WHEN** a resource is recorded `COMPLETED` under one event and the configured event later changes
- **THEN** `get`/`entry` for that bare filename still returns the `COMPLETED` row, unaffected by the event change

#### Scenario: The key carries no event scoping
- **WHEN** two configured events would reference the same resource
- **THEN** they resolve to the **same** ledger key (the bare filename), so a single `COMPLETED` row serves both

### Requirement: Requested-state reset

`LedgerBackend` SHALL provide `clearRequested()`: a bulk delete of **every row whose state is
`REQUESTED`**, leaving `COMPLETED` and `FAILED` rows untouched. It SHALL emit exactly one `changes`
signal on success (like `clear`/`resetTo`). On the SQLDelight backend it SHALL be a single indexed-by
-state `DELETE … WHERE state = 'REQUESTED'`.

`clearRequested` is an **app-side reset-family** operation — in the same family as `clear()` and
`resetTo()`, **not** one of the writer-only prunes (`deleteByAssetId`/`retainAssets`). It SHALL be
callable on the `LedgerBackend` **without** a `LedgerWriter`, so a non-writer holder of the backend may
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

`clear()` SHALL remain on the `LedgerBackend` seam (it is the semantic basis of `resetTo` and is used
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

