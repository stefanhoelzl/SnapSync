## ADDED Requirements

### Requirement: Requested-state reset

`LedgerBackend` SHALL provide `clearRequested()`: a bulk delete of **every row whose state is
`REQUESTED`**, leaving `COMPLETED` and `FAILED` rows untouched. It SHALL emit exactly one `changes`
signal on success (like `clear`/`resetTo`). On the SQLDelight backend it SHALL be a single indexed-by
-state `DELETE … WHERE state = 'REQUESTED'`.

`clearRequested` is an **app-side reset-family** operation — in the same family as `clear()` and
`resetTo()`, **not** one of the writer-only prunes (`deleteByAssetId`/`retainAssets`). It SHALL be
callable on the `LedgerBackend` **without** a `LedgerWriter`, so the app can invoke it (the app
constructs no `LedgerWriter`). It is the recovery for jobs the OS wiped when the extension was
disabled: those resources remain `REQUESTED` in the ledger, the engine never re-issues a `REQUESTED`
key, and there is no API to enumerate live jobs to detect the orphan — so a bulk `REQUESTED` clear is
the only way to let the next discovery re-create them. Clearing **all** `REQUESTED` is correct because
a disable wipes **all** in-flight jobs at once, so no genuinely-in-flight row is lost.

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

#### Scenario: clearRequested holds on the SQLDelight backend

- **WHEN** the clearRequested storage-seam scenarios run against the SQLDelight backend on a JVM
  sqlite driver via the shared backend contract
- **THEN** they pass unchanged (a single state-scoped delete, one change signal)
