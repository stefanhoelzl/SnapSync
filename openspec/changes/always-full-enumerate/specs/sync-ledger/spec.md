## ADDED Requirements

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
