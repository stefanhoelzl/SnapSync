# sync ledger Specification

## Purpose

The engine's durable per-key upload memory: a backend storage seam (dumb row store that signals
its own changes), a three-way capability split — reader (per-key, engine-facing), writer
(records, owned by named code, codified by construction), watcher (aggregate stream,
status-facing) — and self-contained idempotent record operations. The ledger is what makes
skipping provable, reports absorbable (at-least-once), full re-enumeration harmless, and status
a read-only projection.

**Every write is owned by code and guarded in one transaction** — the load-bearing invariant. The cycle's
`LedgerWriter` records, a transport's guarded terminal write settles, and the membership use-cases' reset family
(the join-time load, the leave's clear, the device reset) replaces; each write carries its own guard in its own
statement. How many **processes** hold a writer at once is not an invariant: on iOS ≥26.1 under a full grant
both the app and the upload extension run a cycle over the one App-Group ledger, and write-after-act keeps an
overlap to a duplicate upload of the same object (`changes/archive/2026-09-22-both-uploaders-active`, which retired the "single record-writer
per platform" reading of this seam). Codifying the split as three capabilities — reader, writer, watcher —
keeps which code may write a compile-time fact rather than a convention.

Decision record: `changes/archive/2026-06-12-sync-engine-ledger`.

The **Lifecycle transitions never clear the ledger** requirement was added in
`changes/archive/2026-07-12-fix-app-driven-upload-lifecycle` and reversed in `changes/archive/2026-09-21-join-loads-leave-clears`: the ledger is the current
membership's share set — a leave clears it, a first join or a switch loads it from the device's stored-file
listing — and the per-asset progress read that lets status count only admitted photos was added there too. The `eventId` provenance column, the
`4.sqm` migration, and the backfill sweep were added in
`changes/archive/2026-07-18-add-ledger-event-provenance` (migration step 11b), and removed again — see below.

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

Deleting in-flight (`REQUESTED`) rows along with their departed asset, treating a read partial-grant selection
as an authoritative walk (de-selecting is deleting), the per-row enqueue that retired the resolve chunk, the
per-key read on `TransferRecord`, and the foreground settle as a second `markTerminal` caller came from
`changes/archive/2026-09-22-selection-is-the-walk`.

The `FAILED` state (merged into `DISCOVERED`: a failure returns its row to the work source) and the `attempt`,
`eventId` and `absent` columns, with the provenance and absence-mark sweeps, were retired by the `10.sqm`
migration in `changes/archive/2026-09-21-shrink-the-ledger-row` — a one-way door whose rollback is a roll-forward.
## Requirements
### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerStore` interface with the row
read `get(key): LedgerEntry?`, the guarded record write `recordUnlessSettled(entry): Boolean` (see
"Record operations" — a single-row upsert that never overwrites a row in a done state, and answers whether
it applied), the guarded terminal write
`markTerminal(key, outcome): Boolean` (see "Guarded terminal write"), the bounded state-scoped read of
rows that **need a job** (see "The DISCOVERED state and the ledger as the upload work source"), the
manifest projection read and its detail backfill, the aggregate read
`aggregates(): LedgerAggregates`, the per-asset done-ness read `assetProgress()` (see "Per-asset
progress read"), a change signal `changes: Flow<Unit>`, `clear()` — a
delete-all reset, `resetTo(entries)` — an **atomic**
delete-all-then-insert-all replacement, the key-targeted delete `deleteKeys(keys)` — delete exactly the
rows whose `key` is among the arguments and no other — and the batch record write `recordAllUnlessSettled(entries)`
(see "A walk re-reads only the assets the ledger does not fully know").

There is deliberately **no** unconditional per-row upsert (`put`). It was removed when the record path became
guarded: with no production caller left, it could only serve as an unguarded door for the next production
write. Tests seed a store through the guarded record write or `resetTo`, exactly as production writes it.

There is deliberately **no** bulk demote of `REQUESTED` rows and **no** read of the `REQUESTED` keys. Both
served only the repairs of a `REQUESTED` row whose transfer was gone — the registration ritual's demote and the
stranded reconciliation — and those repairs existed only because a hand-off between the two uploaders
cancelled or orphaned in-flight work. Both uploaders are now active and nothing hands off, so nothing orphans a
row (capability `upload-lifecycle`). Decision record: `changes/both-uploaders-active`.

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
`clear()`, `resetTo`, an
applied `deleteKeys`, an
applied record write (a batch record write that applied to any row signals once for the whole batch), and an applied
`markTerminal` SHALL each remove (and, for `resetTo`, then insert) or
update the matching rows and signal `changes` **once** (so watchers re-read the
now-current truth).
`clear()`, `resetTo`, and `deleteKeys` are **reset/bulk** operations, not the
per-key **record** operations; recording per-upload facts remains the job of the code that owns it (a cycle's
`LedgerWriter`), so a holder of the backend with no writer may reset the store without breaching the
ledger's writer invariant (see "Reader and writer capability split"). `markTerminal` is a **record** operation
and is exposed here deliberately — see "Reader and writer capability split" for why that does not breach the
invariant. `assetId` is a second
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
- **THEN** it declares no operation that deletes rows by state, and no bulk operation over `REQUESTED` rows at
  all — a `REQUESTED` row leaves that state only through its own job's guarded terminal write, a per-key
  record, or the reset family

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

`aggregates()` counts **every** row the ledger holds, whatever the membership admits. It serves the callers
that ask about the ledger as a whole — the extension's decision whether work remains, and the diagnostic
dump. Status does not count from it: it counts from the sibling `assetProgress()` read, intersected with the
membership's admitted set (see "Per-asset progress read").

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

### Requirement: Change signal

`LedgerStore.changes` SHALL emit `Unit` after every write that changed the store — every applied record
write, applied guarded write, and reset/bulk operation. A record write the guard declined changed nothing and
SHALL NOT signal. A ding carries no payload and
promises nothing beyond "re-read the truth" — consumers MUST treat it as a level trigger (conflation,
duplicate dings, and signals missed while busy are all safe because every re-read queries current state).
The signal is **in-process only**: the ledger is shared by both processes — each may write it (see
"Reader and writer capability split") — but neither observes the other's writes through a signal; the app's
status re-reads the ledger on its own triggers (capability `sync-status`, "LedgerCountsSource seam"). So
the backend SHALL NOT post any cross-process (Darwin) notification, and there is no cross-process observer to
merge. The seam itself does not change.

#### Scenario: An applied record dings

- **WHEN** a collector is active on `changes` and a record write applies
- **THEN** the collector receives an emission

#### Scenario: A declined record does not ding

- **WHEN** a collector is active on `changes` and a record write is declined because the row is in a done state
- **THEN** the collector receives no emission

#### Scenario: No cross-process notification is posted

- **WHEN** either process records within a cycle
- **THEN** no cross-process (Darwin) notification is posted, because no other process observes the ledger
  through a signal

### Requirement: Reader and writer capability split

The ledger SHALL expose a concrete shared `LedgerWriter` carrying both the record operations and the
per-key query (`entry(key): LedgerEntry?`). Record and query semantics SHALL be implemented once in
this shared class, delegating storage to the injected `LedgerStore`. There SHALL be no separate
reader type: the writer is constructed only by the upload cycle's shared assembly (`uploadCore`), once per
process that runs a cycle — the app on **every** iOS version, and the extension on iOS ≥26.1 — and components
that must not record are simply never handed a writer: status read access goes through `LedgerStore`'s read
operations (`assetProgress()`, per `sync-status`), never through a writer instance. The app also invokes the
**reset family** (`clear()`, `resetTo`) on the `LedgerStore` at membership transitions — a leave clears, a
join loads (see "The ledger is the current membership's share set") — through the use cases that own them,
not through a writer. Those are not record operations (see "Storage seam — dumb row store").

**The invariant is code ownership of each write, and that each write is one guarded transaction** — not how
many processes write. Every ledger write SHALL be exactly one of: the running cycle's `LedgerWriter` record
family (and its key-scoped prune), a guarded `markTerminal` — a transport's, or the app's foreground settle of
in-flight rows the backend already stores (capability `upload-state-reconciliation`, "Foreground settles
in-flight rows the backend already stores") — or a named reset-family use case (the join-time load's
`resetTo`, the leave's `clear()`, the device reset). Each SHALL be one storage
transaction whose guard, where it has one, is inside the statement (see "Storage seam — dumb row store"), so
it is safe against any other write landing between its read and its write — from the same process or the
other one. How many processes hold a `LedgerWriter` at once is **not** an invariant: on iOS ≥26.1 under a
full grant both the app's and the extension's cycles run over the one shared ledger, and a cycle in either
process picks only `DISCOVERED` rows and records `REQUESTED` only after its job was created (write-after-act,
capability `sync-engine`), so two cycles overlapping can at worst each upload the same key's identical bytes
to the same destination, and the second terminal write of the pair is a declined no-op. Decision record:
`changes/both-uploaders-active`.

Handing a writer instance only to the cycle is the **mechanism** that confines the record family to the code
that owns it. That mechanism is deliberately relaxed for one operation: `markTerminal` (see "Guarded terminal
write") is declared on **`TransferRecord`** — a narrow interface `LedgerStore` extends, carrying only
`markTerminal` and two reads: `entryForDestination` (see "The ledger records the destination a job was sent
to") and `get(key)`, the per-key row read. A transport needs the second read to tell a job whose row an
authoritative walk deleted from one it can still settle (capability `ios-photokit-upload`, "Completion and
retry adjudication"; decision record `changes/selection-is-the-walk`, D3) — because the party the platform tells that an upload terminated is a platform callback, and it cannot
suspend. The ownership holds — the terminal write belongs to the transport whose job terminated, or to the foreground
settle that found the key's bytes stored, and its guard applies it only to a row still `REQUESTED` — while the
type-level codification does not cover it. A spec or a
review that reads the type-level rule as the invariant will reach the wrong conclusion about this call, which
is why both are stated.

A **transport** — an implementation of the upload transfer lifecycle (`BackgroundTransfer`) — SHALL receive a
`TransferRecord` and SHALL NOT receive a `LedgerStore`. What a transport may touch in the ledger is therefore
exactly the one guarded terminal write and the two row reads; every other read and write belongs to the
cycle.

No record operation other than `markTerminal` SHALL be added to `TransferRecord` or to `LedgerStore` on this
argument; a further record operation belongs on the writer. The foreground settle is a second *caller* of
`markTerminal`, not a second operation, and it SHALL record only `COMPLETED`.

#### Scenario: Writer reads what it wrote

- **WHEN** a `LedgerWriter` records an entry and `entry(key)` is called on the same instance
- **THEN** the recorded entry is returned

#### Scenario: Record access exists only where the writer is constructed

- **WHEN** a component is composed without receiving the cycle's `LedgerWriter`
- **THEN** it has no record operation available beyond `markTerminal` — it can otherwise read the ledger
  only through `LedgerStore`'s read operations, and write it only through the reset family

#### Scenario: Both processes' cycles record over one ledger

- **WHEN** on iOS ≥26.1 under a full grant the app's cycle and the extension's cycle each record through their
  own `LedgerWriter`, and a platform callback records a terminal upload through `TransferRecord`
- **THEN** every write is one guarded transaction owned by that code, and no write overwrites a settled row
  or claims a job another cycle created

#### Scenario: A transport holds only the narrow surface

- **WHEN** a transport adapter is composed
- **THEN** it is handed a `TransferRecord`, and no other ledger read or write is reachable from it

#### Scenario: The app resets the ledger through the reset family

- **WHEN** the app leaves an event or loads a join, on any iOS version
- **THEN** the owning use case calls `clear()` or `resetTo` on its `LedgerStore`, not a `LedgerWriter`, and
  records no per-key fact

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
already holds. Transitions between non-done states (a retry `DISCOVERED → REQUESTED`, a failed transfer
`REQUESTED → DISCOVERED`) SHALL still apply. A record the guard declined SHALL NOT be silent: the writer SHALL log
it, naming the key and the refused state.

There is **no** writer operation that records `COMPLETED`. A completed upload is a fact the platform reports,
recorded through the guarded `markTerminal` (see "Guarded terminal write"); a resource already known to be
stored is seeded `COMPLETED` by the join-time load's `resetTo` (capability `upload-state-reconciliation`).

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

#### Scenario: A failed transfer still returns a requested row to the work source
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

The key-scoped delete (`deleteKeys`) SHALL be exposed on
`LedgerWriter` (delegating to the backend) and SHALL NOT be exposed on any other app-facing ledger
surface. It is a sync write by the cycle's `LedgerWriter`, not the app-side `clear()` reset, and at
the writer layer it consults no engine state first. Because only the upload cycle's shared assembly constructs
a `LedgerWriter`, prune access is confined to the cycle — in whichever process runs it — preserving the
ledger's code-ownership invariant (see "Reader and writer capability split").

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
  only through the cycle's `LedgerWriter`

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

### Requirement: Atomic baseline reset

`LedgerStore.resetTo(entries)` SHALL replace the entire store with `entries` in a single atomic
transaction: either all prior rows are removed and all `entries` inserted, or — on failure or
interruption — the store is left unchanged (no partial replacement is ever observable). It SHALL emit
exactly one `changes` signal on success. Entries are stored verbatim (the caller supplies `state`);
`resetTo` performs no clock stamping of its own. On the SQLDelight backend it SHALL execute as one
transaction.

The atomic baseline reset (`resetTo`, the clear-then-seed primitive) **is what the join-time load
invokes** at a provision into a new membership — a first join or a switch (capability
`upload-state-reconciliation`, "A join loads the ledger from the per-device listing"). The load
`resetTo`s the ledger to exactly one `COMPLETED` row per resource in the **per-device** listing, so the
clear is what drops every row from before the membership (a previous membership's `DISCOVERED` and
`REQUESTED` rows, or a leftover `COMPLETED` row that would suppress a needed upload) while the
event-independent listing seeds every stored file `COMPLETED` — preserving cross-event dedup so stored
resources never re-upload after a switch, or after a leave and a later join. The bare-filename key is what
makes this safe: a seeded `COMPLETED` row keys identically across events. Being atomic, the reset never
exposes the membership to a half-loaded ledger.

#### Scenario: Interrupted reset leaves the store unchanged
- **WHEN** a `resetTo` transaction fails partway (e.g. an insert errors)
- **THEN** the store retains exactly its pre-call rows and no `changes` signal claims a new baseline

#### Scenario: Reset to a non-empty baseline is observable as a whole
- **WHEN** `resetTo(entries)` succeeds over a previously empty store
- **THEN** `aggregates()` reflects all `entries` at once and `get` returns each supplied entry verbatim

#### Scenario: Reset baseline holds on the SQLDelight backend
- **WHEN** the reset scenarios run against the SQLDelight backend on a JVM sqlite driver
- **THEN** they pass unchanged (a single-transaction replacement, one change signal)

#### Scenario: A join-time resetTo seed preserves cross-event dedup
- **WHEN** the store holds `COMPLETED` rows from a prior membership plus a stale non-`COMPLETED` row, and a switch's join-time load `resetTo`s from the event-independent per-device listing
- **THEN** the listing seeds the still-stored files `COMPLETED` (so none re-upload) and the stale row is dropped by the clear, leaving the ledger as exactly the device's stored files

### Requirement: Event-independent key

The ledger key SHALL be the **bare resource filename** (`<assetId>-<role>.<ext>`), carrying no event
scoping. Because the key is event-independent, a `COMPLETED` row recorded while one event is
configured stays valid and continues to read as `COMPLETED` after the configured event changes — the
ledger neither keys, **reads**, nor **records** by event: no dedup decision, aggregate, backlog read, or skip
consults an event, and a row carries no event id at all. This is what lets cross-event dedup come purely from the join-time load's seed source (a `resetTo`
clear-and-seed from the event-independent per-device listing) without any ledger key change, even though
the ledger itself is cleared at every leave and replaced at every switch (see "The ledger is the current
membership's share set").

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
"Deletion is a presence diff over an authoritative walk"). Under a partial grant the member's selection
**is** the library, from the app's point of view, so leaving the selection is a fact of presence, not of
policy. A read selection snapshot is an authoritative walk, and de-selecting a photo removes its rows
(capability `limited-photo-access`; decision record `changes/selection-is-the-walk`, D1). The policy rules
this requirement protects — the capture range, the direction, the origin exclusions — still remove
nothing. The retired retain-live reconcile was fed the
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
query. On the SQLDelight backend the pending-resource read, the aggregate read, the per-asset progress read, the
manifest projection, and the guarded record write
SHALL each take the done-state set as a bound parameter (`state NOT IN :doneStates` / `state IN
:doneStates`) rather than comparing `state` to `'COMPLETED'`.

Today exactly one state is done (`COMPLETED`), so every read keeps its current meaning. The requirement
exists for the next one: a state added without classifying it SHALL fail to compile, rather than landing
silently on one side of a string comparison.

#### Scenario: A new state must be classified

- **WHEN** a value is added to `LedgerState` and the done-state decision is not updated
- **THEN** the build fails, because the decision is an exhaustive `when` with no `else` branch

#### Scenario: Reads agree on what done means

- **WHEN** the pending-resource read, the aggregate read, the per-asset progress read, and the manifest
  projection run over the same rows
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
`LedgerState`. The outcome names what the **platform** reported; the state it records is the ledger's answer:
`COMPLETED` records `COMPLETED` — the platform reported the upload succeeded, and no further work is owed for
the key — and `FAILED` records `DISCOVERED`, returning the row to the work source (see "The DISCOVERED state and
the ledger as the upload work source"). This is the
one record operation a platform callback reaches through `TransferRecord` rather than through the writer (see
"Reader and writer capability split"), which is why the set it may record is fixed by its type rather than by
convention: a callback SHALL NOT be able to claim that a job exists (`REQUESTED`) through it.

It has exactly one caller that is not a platform callback: the app's foreground settle, which records
`COMPLETED` for a `REQUESTED` row whose bytes the backend's per-device listing reports stored (capability
`upload-state-reconciliation`, "Foreground settles in-flight rows the backend already stores"). That caller
uses the same operation, under the same guard, and SHALL NOT record `FAILED`. The guard is what lets it run
beside a cycle with no shared lock, as the callback does (decision record `changes/selection-is-the-walk`,
D4).

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

#### Scenario: The foreground settle and a late acknowledgement converge

- **WHEN** the foreground settle records `COMPLETED` for a `REQUESTED` row, and the OS later acknowledges
  that row's job as succeeded
- **THEN** the acknowledgement's guarded write applies to nothing, and the row stays `COMPLETED`

### Requirement: The DISCOVERED state and the ledger as the upload work source

`LedgerState` SHALL carry a `DISCOVERED` value meaning **the resource's asset was admitted and its key needs an
upload job: no job is in flight for it and its bytes are not on the backend**. It SHALL be recorded for
every resource a cycle's walk admitted and the engine judged to be new work, **before** any upload
job is created for that cycle. It SHALL also be what a failed upload returns its row to — through the engine's
failure record or a transport's terminal write — because a
failure and a never-attempted discovery are the same fact to a producer. Nothing else returns a `REQUESTED` row
to it: there is no stranded reconciliation and no bulk demote, because nothing orphans a `REQUESTED` row any
more (decision record: `changes/both-uploaders-active`). There is no separate failed state:
the engine retries forever with no attempt budget, so "an attempt was already made" decides nothing.
`LedgerState` therefore has exactly three values: `DISCOVERED`, `REQUESTED` and `COMPLETED`.

The ledger SHALL be the upload cycle's **source of work**: a producer SHALL enqueue from the ledger's
rows rather than from the walk's return value, so a cycle can make progress on work it already knows
about whatever the walk returns. Every walk is a full enumeration, but it re-reads resources only for assets
the ledger does not fully know (see "A walk re-reads only the assets the ledger does not fully know"), so a
row that needs a job is found by this read, never re-derived by the walk. The `LedgerStore` SHALL expose a
state-scoped read of the rows that need a job, and it SHALL return the `DISCOVERED` rows — whether never
attempted or returned there by a failure.

A cycle in **either** process SHALL pick its work only from these `DISCOVERED` rows, and SHALL NOT create a job
for a `REQUESTED` or `COMPLETED` row. With both uploaders active over one ledger, that is what keeps a second
cycle off the first one's in-flight work: a key the first cycle recorded `REQUESTED` is never offered to the
second, so only two cycles picking the same `DISCOVERED` key before either records can duplicate an upload —
identical bytes to the same destination, converged by the guarded terminal write (see "Reader and writer
capability split").

A row needing a job records that the policy admitted its asset **when the row was written**, which is not
the same fact as the membership's *current* admission (`photo-selection-policy`). The cycle SHALL
therefore admit the rows this read returns before resolving or enqueuing any of them, and any bound on
how much work one cycle takes SHALL be applied to the **admitted** rows — bounding what a cycle
**resolves**, never what it reads. A bound applied to the read instead can starve: rows are returned in a
stable key order, so excluded rows sorting ahead of admitted ones would fill the bound on every cycle and
admitted work further down would never be reached. The cycle resolves the admitted rows **one at a time**
and creates each one's job until the platform refuses (`LIMIT_EXCEEDED`), so a refusal wastes no resolve.
Creation is bounded only by the platform's refusal, which follows the admitted rows (capability
`sync-engine`; decision records: `changes/both-uploaders-active`, and `changes/selection-is-the-walk` D5,
which retired the resolve chunk).

A row this read returned whose key the platform resolves to **nothing** SHALL have that row, and only that
row, deleted (see "Deletion is a presence diff over an authoritative walk"). Its asset has left the library,
or, under a partial grant, left the selection. Either way it can no longer be uploaded, and a row that
still needs a job for it would be offered on every cycle. Deleting by key rather than by asset is what
leaves the asset's settled rows alone: this read selects rows by key.

`DISCOVERED` SHALL NOT be a done state, so a row in it counts toward the backlog everywhere. It SHALL
nonetheless be **included** in the device-manifest projection: the manifest declares what this device
intends to provide, and a resource the walk found and the policy admitted is precisely that (capability
`device-manifest`).

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

#### Scenario: A second cycle does not re-pick an in-flight row

- **WHEN** one cycle has created a job for a key and recorded it `REQUESTED`, and a cycle in the other process
  then reads the work source
- **THEN** that key is not among the rows needing a job, and the second cycle creates no job for it

#### Scenario: The bound applies to the resolved work, not the read

- **WHEN** the rows needing a job exceed what the platform accepts in one cycle and some of them are excluded
  by the membership's current policy
- **THEN** the cycle resolves and creates only **admitted** rows until the platform refuses, and the excluded
  ones neither consume the bound nor prevent admitted rows from being enqueued

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

The **bytes believed stored** axis is **retired**. It existed so a comparison against the backend's own
listing could ask which rows assert that an upload landed, independently of what "settled" means. Its one
reader, the read-only foreground check, is deleted (capability `upload-state-reconciliation`), and an axis
nothing reads is a classification that can only drift. A future comparison against the backend SHALL
re-introduce it as its own decision rather than borrow **done**.

A state added without classifying it on **every** axis SHALL fail to compile, rather than landing
silently on one side of any of them. The axes are therefore open-ended by construction: adding one is
ordinary, and it is what stops a new state from being filed by a query's string comparison instead of by
a decision.

#### Scenario: A new state must be classified on every axis

- **WHEN** a value is added to `LedgerState` and any one of the classifications is not updated
- **THEN** the build fails, because each decision is an exhaustive `when` with no `else` branch

#### Scenario: The classifications are independent

- **WHEN** the classifications are applied to `REQUESTED` and `COMPLETED`
- **THEN** `REQUESTED` is neither done nor in need of a job, while `COMPLETED` is done and needs no job —
  so a read of one set never implies another

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

1. **The walk is authoritative.** The cycle's discovery reported `fullEnumeration`. Either it read the
   library itself under a full grant and the read succeeded, so every asset inside the policy's capture
   window was returned; or, under a partial grant, it returned a selection snapshot that **has been read**,
   which is the whole gallery from the app's point of view (capability `limited-photo-access`). An
   unreadable library returns nothing and is not evidence of absence. A selection that has not been read
   yet never reaches a walk: the app's cycle is withheld while it is unread. A walk that is not
   authoritative SHALL delete nothing.
2. **The row is inside the walk's window.** The row's asset is admitted by the membership's policy through
   the same row-admission derivation the device manifest and the enqueue use (`admittedAssetIds`). The
   ledger holds rows outside the window — the join-time load seeds everything the device ever stored, for
   any event — and the walk is bounded by the policy's capture range, so a row outside that range is not
   evidence either way. A bare row (empty `creationDate`) is never admitted, so it is never
   deleted this way.
3. **The asset is absent from the walk.** Presence SHALL be the asset ids of **every** candidate the walk
   returned, before admission. Being in the library is not a question of scope.

A row's upload state SHALL NOT exempt it. A `REQUESTED` row is deleted like any other. Its transfer may
still complete, and then its guarded terminal write matches no row and applies to nothing ("Guarded
terminal write"). The bytes land and are listed in no manifest, because the manifest projects the rows. A
failure or retry the platform later presents for that key SHALL write nothing and SHALL NOT be retried
(capability `upload-lifecycle`, "A presented job whose row is gone is answered and nothing more"). The
earlier exemption kept a photo that had left the library (or the selection) listed until its job settled.
It existed only because a late terminal write for a missing row was treated as an anomaly, and it no longer
is one (decision record `changes/selection-is-the-walk`, D2).

The cycle SHALL decide the deletion from the same walk that supplies presence, and SHALL apply it through
`deleteKeys` before it records that walk's discoveries and before it publishes the device manifest, so a
departed photo is never listed by the cycle that saw it leave.

A second path deletes one row at a time: a ledger key the cycle asked the platform to resolve for a job that
resolves to **nothing** SHALL have **that row** deleted, and no other (see "The DISCOVERED state and the
ledger as the upload work source"). It needs no authoritative gate, because it only ever reaches a row that
still needs a job, and deleting one costs a re-discovery, never a photo. It SHALL NOT be reached with a
selection that has not been read: an unread scope answers no resolution at all (capability
`limited-photo-access`), because an empty answer there would delete every admitted row that needs a job.

#### Scenario: A departed in-window asset's rows are deleted
- **WHEN** an authoritative walk does not return asset `X`, whose `COMPLETED` rows carry a capture date the
  policy admits
- **THEN** the cycle deletes those rows before recording its discoveries, and the manifest it publishes no
  longer lists `X`

#### Scenario: A row outside the walk's window is kept
- **WHEN** the ledger holds a `COMPLETED` row whose capture date is before the membership's cutoff (dated
  while an earlier cutoff admitted it), and an authoritative walk does not return its asset
- **THEN** the row is kept

#### Scenario: A bare row is never deleted by the walk
- **WHEN** the ledger holds a row with an empty `creationDate` and an authoritative walk does not return its
  asset
- **THEN** the row is kept

#### Scenario: A read selection snapshot deletes a de-selected photo's rows
- **WHEN** the cycle runs under a partial grant with a read selection snapshot, and the member has
  de-selected a photo whose `COMPLETED` row is in-window
- **THEN** the discovery is authoritative, the photo's rows are deleted, and the manifest that cycle
  publishes no longer lists it

#### Scenario: An un-read selection deletes nothing
- **WHEN** the app's cycle runs under a partial grant before the selection has been read
- **THEN** the cycle is withheld and no row is deleted, by the walk or by the enqueue's resolve

#### Scenario: An unreadable walk deletes nothing
- **WHEN** the platform reports the library not readable and the discovery returns no candidates
- **THEN** no row is deleted

#### Scenario: An in-flight row is deleted with its asset
- **WHEN** an authoritative walk does not return asset `X`, one of whose rows is `REQUESTED`
- **THEN** all of `X`'s in-window rows are deleted, including the `REQUESTED` one, and the manifest that cycle
  publishes no longer lists `X`

#### Scenario: A late completion for a deleted row writes nothing
- **WHEN** the transfer of a `REQUESTED` row the walk deleted then succeeds
- **THEN** its guarded terminal write applies to no row, no row is created, and `X` stays unlisted

#### Scenario: A late failure for a deleted row writes nothing
- **WHEN** the platform presents a failure for a key whose row the walk deleted
- **THEN** no row is recorded for that key, no retry is made, and no job is created

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

The join-time load produces bare rows, so an asset whose stored-file listing named only some of its roles is
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
- **WHEN** a join-time load seeded only `X-primary.heic` (bare), and the walk returns `X`
- **THEN** the cycle reads `X`'s resources, fills the primary row's detail, and records `X-live.mov`
  `DISCOVERED`

### Requirement: The ledger is the current membership's share set

The upload ledger SHALL hold the **current membership's share set**: nothing while the device is not
joined, loaded at a join, cleared at a leave, and replaced at a switch. Every change of membership is an
explicit app action, so the ledger is set at that action rather than detected afterwards:

- a **leave** SHALL `clear()` the upload ledger, after uploads are stopped and before the config is cleared
  (the order and its best-effort independence are owned by capability `leave-event`);
- a **provision into a new membership** — a first join, or a switch to a different event — SHALL clear
  and then load the ledger through the join-time load: `resetTo` from the per-device listing on success,
  `clear()` on failure (capability `upload-state-reconciliation`, "A join loads the ledger from the
  per-device listing"). A switch stops uploads first (capability `upload-lifecycle`), so it is a leave
  followed by a join.

No other membership transition SHALL clear, reset, or reload the ledger: a re-provision of the
already-joined event, a permission change, a direction change, and a reconfigure of the joined event
(including a widening one, which does not re-fetch the listing) leave every row in place.

Only the **upload** ledger is cleared. The download store's handle-carrying rows stay permanent
(capability `download-store`): they are what stops the device uploading its own imports back into an
event.

The leave's clear costs no dedup. The byte store is device-partitioned and event-independent, and the next
join's load seeds every resource the backend holds for the device `COMPLETED` again, so nothing already
stored re-uploads unless that load fails (whose cost, idempotent re-uploads bounded by the event window, is
stated by capability `upload-state-reconciliation`). The clear at a join is what makes a leftover row
harmless: a `COMPLETED` row from before the membership — kept by a leave under the earlier contract, or
left when a leave's best-effort clear failed — would otherwise suppress a needed upload forever.

Two late writers can reach a cleared ledger, and neither SHALL be treated as a fault:

- a **completion** already in flight when the ledger was cleared finds no row: `entryForDestination`
  answers nothing, the outcome is acknowledged and discarded, and the bytes are on the backend with no row.
  The next join's listing includes them, so its load seeds them `COMPLETED`;
- a **cycle already running** in either process when the app clears checked membership once at its start and
  may still record rows. Those can only be `DISCOVERED` or `REQUESTED` (no record operation writes
  `COMPLETED`), they lie outside any membership so no deciding reader acts on them, and the next join's clear
  removes them.

The ledger **key** stays event-independent (see "Event-independent key"); only the ledger's contents are
scoped to the membership. This deepens the single-active-membership assumption: concurrent multi-event
membership, a named future, would need per-event ledgers or a membership column.

`clear()` is also invoked by device-state reset (capability `device-state-reset`), which is not a membership
transition of this list.

#### Scenario: Leaving clears the upload ledger

- **WHEN** the user leaves the currently-joined event on either tier
- **THEN** after uploads are stopped the upload ledger holds no rows, and the download store's rows are
  unchanged

#### Scenario: A first join loads the ledger

- **WHEN** a device with no membership joins an event and the per-device listing fetch succeeds
- **THEN** the ledger holds exactly one bare `COMPLETED` row per resource the backend holds for the device

#### Scenario: A switch clears and loads

- **WHEN** the device switches to a different event while the ledger holds the previous membership's rows
- **THEN** uploads are stopped, and the ledger then holds only what the join-time load wrote — the listing's
  rows on success, nothing on failure

#### Scenario: Other transitions keep every row

- **WHEN** the joined event is re-provisioned, the photo permission changes, the direction changes, or the
  joined event is reconfigured
- **THEN** the ledger retains every row, in the state it had

#### Scenario: A completion after the clear is discarded

- **WHEN** an upload that was in flight at a leave completes after the ledger was cleared
- **THEN** no row is found for its destination, nothing is written, and the next join's load seeds the stored
  resource `COMPLETED`

#### Scenario: A late cycle's rows are removed at the next join

- **WHEN** a cycle in either process that started before a leave records `DISCOVERED` rows after the app cleared the
  ledger
- **THEN** those rows are dropped by the next join's `resetTo` or `clear()`, and no upload is made for them
  in between

### Requirement: Per-asset progress read

`LedgerStore` SHALL provide `assetProgress()`: one entry per `assetId` the ledger holds a row for, answering
whether **every** row of that asset is in a **done** state (see "The done-state set is decided in Kotlin").
It is the same per-asset collapse `aggregates()` performs, **un-counted**: an asset whose rows are all done is
done, and an asset with at least one non-done row is not. It carries no timestamp and no key.

It SHALL be computed in **one** snapshot-consistent read, so the done and not-done answers can never
disagree with each other about a row that moved between two reads. On the SQLDelight backend it SHALL be a
single `assetId`-grouped query taking the done-state set as a bound parameter, and every `LedgerStore`
implementation SHALL satisfy it through the shared `LedgerStoreContract`.

It exists because status counts only what the membership admits (capability `sync-status`): the ledger
holds rows for every resource the device has stored for any event — the join-time load seeds them all —
so a whole-ledger count would mask pending in-window photos behind historical completions. Status
intersects this read with the admitted asset set the gallery counts for `N` (capability `gallery-status`),
which needs no derivation of the policy on the status poll. The ledger interprets nothing about admission:
the intersection is the caller's.

#### Scenario: A photo is done only when all its resources are

- **WHEN** asset `A` has two `COMPLETED` rows and asset `B` has one `COMPLETED` and one `DISCOVERED` row
- **THEN** `assetProgress()` answers `A` done and `B` not done, and nothing else

#### Scenario: An empty ledger answers nothing

- **WHEN** `assetProgress()` is called on an empty store
- **THEN** it answers no entries

#### Scenario: It agrees with the aggregate read

- **WHEN** `assetProgress()` and `aggregates()` run over the same rows
- **THEN** the number of done entries equals `completed` and the number of not-done entries equals
  `pending`

#### Scenario: A photo is done as soon as its last upload is recorded

- **WHEN** an asset's only `REQUESTED` row is recorded `COMPLETED` through `markTerminal`, and no cycle has
  run since
- **THEN** `assetProgress()` answers that asset done

