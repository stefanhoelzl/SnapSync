## ADDED Requirements

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
- a **cycle already running** in the extension when the app clears checked membership once at its start and
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

#### Scenario: A late extension cycle's rows are removed at the next join

- **WHEN** an extension cycle that started before a leave records `DISCOVERED` rows after the app cleared the
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

## MODIFIED Requirements

### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerStore` interface with the row
read `get(key): LedgerEntry?`, the guarded record write `recordUnlessSettled(entry): Boolean` (see
"Record operations" — a single-row upsert that never overwrites a row in a done state, and answers whether
it applied), the guarded terminal write
`markTerminal(key, outcome): Boolean` (see "Guarded terminal write"), the state-scoped read of `REQUESTED` keys, the bounded state-scoped read of
rows that **need a job** (see "The DISCOVERED state and the ledger as the upload work source"), the
manifest projection read and its detail backfill, the aggregate read
`aggregates(): LedgerAggregates`, the per-asset done-ness read `assetProgress()` (see "Per-asset
progress read"), a change signal `changes: Flow<Unit>`, `clear()` — a
delete-all reset, `demoteRequested()` — return every `REQUESTED` row to `DISCOVERED` (see "Requested-state reset"), `resetTo(entries)` — an **atomic**
delete-all-then-insert-all replacement, the key-targeted delete `deleteKeys(keys)` — delete exactly the
rows whose `key` is among the arguments and no other — and the batch record write `recordAllUnlessSettled(entries)`
(see "A walk re-reads only the assets the ledger does not fully know").

There is deliberately **no** unconditional per-row upsert (`put`). It was removed when the record path became
guarded: with no production caller left, it could only serve as an unguarded door for the next production
write. Tests seed a store through the guarded record write or `resetTo`, exactly as production writes it.

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
`clear()`, `demoteRequested()`, `resetTo`, an
applied `deleteKeys`, an
applied record write (a batch record write that applied to any row signals once for the whole batch), and an applied
`markTerminal` SHALL each remove (and, for `resetTo`, then insert) or
update the matching rows and signal `changes` **once** (so watchers re-read the
now-current truth).
`clear()`, `demoteRequested()`, `resetTo`, and `deleteKeys` are **reset/bulk** operations, not the
per-key **record** operations; recording per-upload facts remains the single record-writer's job, so a
non-writer holder of the backend may reset the store without breaching the
single-record-writer invariant. `markTerminal` is a **record** operation and is exposed here deliberately —
see "Reader and writer capability split" for why that does not breach the invariant. `assetId` is a second
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


### Requirement: Reader and writer capability split

The ledger SHALL expose a concrete shared `LedgerWriter` carrying both the record operations and the
per-key query (`entry(key): LedgerEntry?`). Record and query semantics SHALL be implemented once in
this shared class, delegating storage to the injected `LedgerStore`. There SHALL be no separate
reader type: the writer is constructed only by the composition root that owns the engine (one per
platform), and components that must not record are simply never handed a writer — app-side read
access goes through `LedgerStore`'s read operations (`assetProgress()`, per `sync-status`), never
through a writer instance. The app also invokes the **reset family** (`clear()`, `resetTo`) on the
`LedgerStore` at membership transitions — a leave clears, a join loads (see "The ledger is the current
membership's share set") — on **every** tier, including iOS ≥26.1, where it holds no writer. Those are not
record operations (see "Storage seam — dumb row store"), so this does not breach the invariant below.

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
  only through `LedgerStore`'s read operations, and write it only through the reset family

#### Scenario: One process records

- **WHEN** the platform callback records a terminal upload through `TransferRecord` and the cycle records
  through the `LedgerWriter`
- **THEN** both are inside the single record-writing process for that tier, and no second process records

#### Scenario: A transport holds only the narrow surface

- **WHEN** a transport adapter is composed
- **THEN** it is handed a `TransferRecord`, and no other ledger read or write is reachable from it

#### Scenario: The app resets the ledger without a writer

- **WHEN** the app leaves an event or loads a join on iOS ≥26.1, where the extension is the one recording
  process
- **THEN** it calls `clear()` or `resetTo` on its `LedgerStore`, holds no `LedgerWriter`, and records no
  per-key fact


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
already holds. Transitions between non-done states (a retry `DISCOVERED → REQUESTED`, a failed or stranded
transfer `REQUESTED → DISCOVERED`) SHALL still apply. A record the guard declined SHALL NOT be silent: the writer SHALL log
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

#### Scenario: A stranded transfer still returns a requested row to the work source
- **WHEN** `recordFailed` is called for a key whose row is `REQUESTED`
- **THEN** `entry(key)` has state `DISCOVERED`

#### Scenario: The reset family still replaces settled rows
- **WHEN** `resetTo` is called with entries for keys whose rows are `COMPLETED`
- **THEN** the store holds exactly the supplied entries, whatever the replaced rows' states were

#### Scenario: The writer cannot record a completion
- **WHEN** a component holds a `LedgerWriter`
- **THEN** it has no operation that records `COMPLETED` for a key


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
   ledger holds rows outside the window — the join-time load seeds everything the device ever stored, for
   any event — and the walk is bounded by the policy's capture range, so a row outside that range is not
   evidence either way. A bare row (empty `creationDate`) is never admitted, so it is never
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
- **WHEN** the ledger holds a `COMPLETED` row whose capture date is before the membership's cutoff (dated
  while an earlier cutoff admitted it), and an authoritative walk does not return its asset
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


## REMOVED Requirements

### Requirement: Lifecycle transitions never clear the ledger

**Reason**: Reversed. It rested on the ledger being device-global dedup state that only an in-cycle,
marker-gated reconciliation could re-baseline, because a delete-and-reinstall was thought to change
membership unobserved. A reinstall now leaves no membership, so every change of membership is an explicit
app action, and dedup across a leave survives by reloading from the per-device listing at the next join.
Keeping the ledger across a leave is also what lets a leftover `COMPLETED` row suppress a needed upload
inside a later window.

**Migration**: Replaced by "The ledger is the current membership's share set": a leave clears the upload
ledger, a first join or a switch clears then loads it, and a re-provision of the joined event, a permission
change, a direction change and a reconfigure still clear nothing. Devices joined when this ships keep
their ledger until their next leave or switch; a device unjoined at update time is cleared by its next
join's load.
