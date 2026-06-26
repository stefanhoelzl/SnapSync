## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: Change signal

`LedgerBackend.changes` SHALL emit `Unit` after every successful `put`. A ding carries no payload
and promises nothing beyond "re-read the truth" — consumers MUST treat it as a level trigger
(conflation, duplicate dings, and signals missed while busy are all safe because every re-read
queries current state). Where the underlying store is written by another process, the backend SHALL
feed `changes` from a cross-process notification — but that cross-process notification is the
**writer process's** signal that its work is durable, and SHALL be posted **once per writer work
cycle**, not after every `put`: the iOS App-Group backend SHALL NOT post a Darwin notification on
each `put`; instead the writer process (the extension) SHALL post one Darwin notification
(a `CFNotificationCenter` darwin-notify name) after its `process()` cycle completes, and the app-process
backend SHALL merge an observer of that notification into its `changes` flow. The in-process `changes`
ding on every `put` is unchanged (it has no in-writer-process consumer). The seam itself does not
change. A missed cross-process notification is harmless (the app re-reads on its next trigger).

#### Scenario: Put dings

- **WHEN** a collector is active on `changes` and `put` completes
- **THEN** the collector receives an emission

#### Scenario: A writer cycle dings the other process once

- **WHEN** the extension process performs several `put`s within one `process()` cycle and a collector
  in the app process is active on `changes`
- **THEN** the app-process collector receives one emission (via the single end-of-cycle Darwin
  notification) and re-reads current truth, rather than one emission per `put`

### Requirement: Ledger watcher

The ledger SHALL expose a third user-facing type alongside reader and writer: `LedgerWatcher`,
whose `snapshot: Flow<LedgerSnapshot>` is a cold flow that emits the current snapshot on collection
and re-queries on every backend ding, with equal consecutive values deduplicated. A `LedgerSnapshot`
SHALL carry `completed` and `newestCompletionAt` (the same scalars as `LedgerAggregates`, reused) and
`pendingByAsset: Map<assetId, Set<key>>` (the backlog grouped by photo), all read **point-in-time
consistently** within one ding so the scalars and the backlog never disagree. Each collection starts
with current truth — collectors share nothing. The watcher is the only ledger type that surfaces the
snapshot or dings; `LedgerReader` stays per-key (`entry(key)` only).

#### Scenario: Collection starts with current truth

- **WHEN** `snapshot` is collected over a store holding one `COMPLETED` key
- **THEN** the first emission reports `completed = 1` and an empty `pendingByAsset`, without any write
  occurring

#### Scenario: A write re-emits a consistent snapshot

- **WHEN** a `REQUESTED` key for a new asset is recorded while `snapshot` is collected
- **THEN** a new `LedgerSnapshot` is emitted whose `pendingByAsset` contains that asset's key and
  whose `completed` is unchanged, both from the same read

#### Scenario: Unchanged snapshot stays silent

- **WHEN** a write does not change the snapshot values (e.g. a `REQUESTED` key re-recorded with a new
  attempt that leaves the backlog and counts identical)
- **THEN** no new emission is observed
