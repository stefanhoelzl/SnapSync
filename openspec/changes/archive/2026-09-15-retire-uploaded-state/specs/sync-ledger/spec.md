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
delete-all reset, `clearRequested()` — delete every `REQUESTED` row, `resetTo(entries)` — an **atomic**
delete-all-then-insert-all replacement, the asset-targeted bulk mark `markAbsent(assetId)` — mark
every row whose `assetId` equals the argument as absent, **keeping** the rows — its inverse
`markPresent(assetIds)` — clear the absence mark of every row whose `assetId` is among the arguments — and
the provenance sweep `backfillEventId(eventId)` (see "Event provenance and the backfill sweep").

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
joined when the row was recorded. `clear()`, `clearRequested()`, `resetTo`, `markAbsent`, an applied record
write, an applied `markPresent`, and an applied
`markTerminal` SHALL each remove (and, for `resetTo`, then insert) or
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

**Downgrade stance (recorded as contract):** a revert of the `UPLOADED` retirement SHALL be **staged** —
keep `8.sqm`, revert only the Kotlin — because the native driver refuses a database newer than the binary's
compiled schema. The reverted build understands `COMPLETED`. Re-applying the retirement after such a revert
SHALL ship a further migration carrying the same rewrite, because `8.sqm` has already run on every device
the revert reached. Decision record: `changes/retire-uploaded-state` (D3).

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

#### Scenario: An UPLOADED row is settled by the migration
- **WHEN** a v8 database holding a row whose stored state is `UPLOADED` is migrated to the current schema
- **THEN** that row reads back `COMPLETED` with every other column intact, and `aggregates()` counts its
  asset completed

#### Scenario: The rewrite touches nothing else
- **WHEN** a v8 database holding `DISCOVERED`, `REQUESTED`, `COMPLETED` and `FAILED` rows is migrated
- **THEN** every one of those rows keeps its state

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

### Requirement: Guarded terminal write

`LedgerStore` SHALL expose `markTerminal(key, outcome): Boolean` — a **single guarded statement** that sets a
row's state **only while that row is still `REQUESTED`**, and answers whether it applied. On the SQLDelight
backend it SHALL be one `UPDATE … WHERE key = :key AND state = 'REQUESTED'` whose applied/not-applied answer
is read inside that statement's own transaction.

`outcome` SHALL be a `TerminalOutcome` — `COMPLETED` or `FAILED`, declared in `:domain` `model/` — and not a
`LedgerState`, so the only states this write can record are the two an upload can terminate in. This is the
one record operation a platform callback reaches through `LedgerStore` rather than through the writer (see
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

## REMOVED Requirements

### Requirement: The UPLOADED state and its promotion
**Reason**: `UPLOADED` meant "the bytes are stored and the work a completion triggers has not run". That work
no longer exists on the completion path: the device-manifest write is the announcement (capability
`upload-completion-notify`), and own-photo album placement happens when the upload is first enqueued
(capability `event-album`). The state only delayed `COMPLETED`.
**Migration**: A successful upload is recorded `COMPLETED` through `markTerminal`. Existing `UPLOADED` rows
are rewritten to `COMPLETED` by `8.sqm` (see "Ledger schema migration").

### Requirement: Guarded promotion
**Reason**: There is no `UPLOADED` row left to promote.
**Migration**: None needed; `markTerminal(key, COMPLETED)` records the settled state directly.

### Requirement: Uploaded-row read
**Reason**: Its only consumer was the promotion pass, which is removed.
**Migration**: None needed; album placement reads the enqueue slice (capability `event-album`).
