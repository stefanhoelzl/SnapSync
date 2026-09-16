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
joined when the row was recorded. `clear()`, `demoteRequested()`, `resetTo`, `markAbsent`, an applied record
write, an applied `markPresent`, and an applied
`markTerminal` SHALL each remove (and, for `resetTo`, then insert) or
update the matching rows and signal `changes` **once** (so watchers re-read the
now-current truth).
`clear()`, `demoteRequested()`, `resetTo`, `markAbsent`, and `markPresent` are **reset/bulk** operations, not the
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

#### Scenario: There is no bulk delete of requested rows
- **WHEN** the `LedgerStore` interface is inspected
- **THEN** it declares no operation that deletes rows by state; the only bulk operation over `REQUESTED` rows
  is `demoteRequested()`, which keeps them

#### Scenario: There is no delete-by-asset
- **WHEN** an asset leaves the device's library
- **THEN** its rows are marked absent and retained, and no seam operation exists that deletes rows by
  `assetId`

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
as the upload work source"): the ledger's own work read returns it on the next cycle, so the recovery needs no
re-enumeration and no discovery-cursor reset. A deleted row could only return through discovery, which a
settled cursor never re-surfaces. Demoting also keeps the row's recorded detail — `assetId`, role, content
type, provenance — which a deletion discarded and a rediscovery had to re-derive.

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
  re-enumerating the library

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
an incremental scan resumes, and a reconciliation clears it whenever it re-baselines
(`upload-state-reconciliation`). What that costs is a
full re-enumeration whose every resource is already `COMPLETED` here — which is precisely why the ledger
is the thing that must not be cleared, and the cursor is not.

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
