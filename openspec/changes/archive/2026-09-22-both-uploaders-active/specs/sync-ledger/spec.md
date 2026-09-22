## MODIFIED Requirements
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
family (and its key-scoped prune), a transport's guarded `markTerminal`, or a named reset-family use case
(the join-time load's `resetTo`, the leave's `clear()`, the device reset). Each SHALL be one storage
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
`markTerminal` and the read `entryForDestination` (see "The ledger records the destination a job was sent
to") — because the party the platform tells that an upload terminated is a platform callback, and it cannot
suspend. The ownership holds — the terminal write belongs to the transport whose job terminated, and its guard
applies it only to a row still `REQUESTED` — while the type-level codification does not cover it. A spec or a
review that reads the type-level rule as the invariant will reach the wrong conclusion about this call, which
is why both are stated.

A **transport** — an implementation of the upload transfer lifecycle (`BackgroundTransfer`) — SHALL receive a
`TransferRecord` and SHALL NOT receive a `LedgerStore`. What a transport may touch in the ledger is therefore
exactly the one guarded terminal write and the one destination lookup; every other read and write belongs to
the cycle.

No record operation other than `markTerminal` SHALL be added to `TransferRecord` or to `LedgerStore` on this
argument; a further record operation belongs on the writer.

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
admitted work further down would never be reached. The cycle resolves the admitted rows in small chunks and
creates jobs until the platform refuses (`LIMIT_EXCEEDED`); the chunk bounds the resolves a refusal wastes,
and creation is bounded only by the platform's refusal (capability `sync-engine`; decision record:
`changes/both-uploaders-active`) — both of which follow the admitted rows.

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



## REMOVED Requirements

### Requirement: Requested-state reset

**Reason**: `demoteRequested()` existed only to recover `REQUESTED` rows whose transfer a hand-off between the
two uploaders had cancelled or orphaned — the PhotoKit re-register's disable wiping every in-flight OS job, a
deregistration of a download-only membership, or an app disarm whose `-999` never landed. Both uploaders are
now active and nothing hands off: the registration spans the membership, a re-provision of the joined event
touches no registration, and revocation cancels nothing. Nothing orphans a row, so nothing repairs one; its
only other reader, the state-scoped `REQUESTED`-keys read of the stranded reconciliation, goes with it.
Decision record: `changes/both-uploaders-active`.

**Migration**: None for data — `demoteRequested` and `requestedKeys` are queries, not tables or columns, and
`Ledger.sq` drops both with no schema change. A `REQUESTED` row now leaves that state only through its own
job's guarded terminal write (`FAILED` returns it to `DISCOVERED`), a per-key record, or the reset family. A
transfer the OS loses silently leaves its row `REQUESTED` — accepted, never observed (capability
`upload-lifecycle`).
