# sync-ledger delta

Generalize the single-record-writer language so the platform-neutral ledger spec stops assuming the
iOS ≥26.1 two-process (app + extension) model. The invariant is unchanged; only its wording is made
platform-agnostic, and the OS-wipe recovery narrative is demoted to *one* motivating example bound to
the PhotoKit tier rather than the definition.

## MODIFIED Requirements

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
