## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerStore` interface with the row
operations `get(key): LedgerEntry?` and `put(entry)` (a single-row upsert), the guarded terminal write
`markTerminal(key, state): Boolean` (see "Guarded terminal write"), the state-scoped read of `UPLOADED` rows
(see "Uploaded-row read"), the aggregate read
`aggregates(): LedgerAggregates`, a change signal `changes: Flow<Unit>`, `clear()` — a
delete-all reset, `resetTo(entries)` — an **atomic** delete-all-then-insert-all replacement, two
asset-targeted bulk removals: `deleteByAssetId(assetId)` — delete every row whose `assetId` equals
the argument — and `retainAssets(keep)` — delete every row whose `assetId` is not in the `keep` set —
and the provenance sweep `backfillEventId(eventId)` (see "Event provenance and the backfill sweep").
Backends SHALL store entries verbatim (no interpretation, no precedence logic, last write wins, no
clocks of their own). A `LedgerEntry` SHALL carry `key`, `assetId`, `state` (`REQUESTED` |
`UPLOADED` | `COMPLETED` | `FAILED`), `attempt`, and `eventId` — the event that was joined when the row was
recorded. `clear()`, `resetTo`,
`deleteByAssetId`, `retainAssets`, and an applied `markTerminal` SHALL each remove (and, for `resetTo`, then
insert) or update the matching
rows and signal `changes` **once** like a `put` (so watchers re-read the now-current truth).
`clear()`, `resetTo`, `deleteByAssetId`, and `retainAssets` are **reset/bulk** operations, not the
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

#### Scenario: Put then get round-trips
- **WHEN** `put(entry)` is called and then `get(entry.key)`
- **THEN** the returned entry equals the one put, field for field — including `assetId` and `eventId`

#### Scenario: A guarded terminal write signals like a put
- **WHEN** `markTerminal` applies to a row
- **THEN** `changes` signals exactly once, as it would for a `put`

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
