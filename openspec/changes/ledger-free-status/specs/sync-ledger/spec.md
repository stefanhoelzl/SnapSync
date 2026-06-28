## REMOVED Requirements

### Requirement: Ledger watcher

**Reason**: The app no longer watches the ledger for status — status is derived from storage (the
completeness listing) and the on-disk manifests, so the status-facing watcher has no consumer. (The app
still seeds via `resetTo` on rejoin until `reconcile-in-extension` relocates the seed into the extension.)
**Migration**: Status uses the `CompletedAssetsSource` and `PendingManifestsSource` seams
(`sync-status`); the `LedgerWatcher` type, its `LedgerSnapshot` flow, and any app-side collection of it
are removed. The engine's per-key `LedgerReader`/`LedgerWriter`, `aggregates()`, record, and reset
operations are retained for the extension's own cycle (and the app's rejoin seed).

## MODIFIED Requirements

### Requirement: Change signal

`LedgerBackend.changes` SHALL emit `Unit` after every successful `put`. A ding carries no payload and
promises nothing beyond "re-read the truth" — consumers MUST treat it as a level trigger (conflation,
duplicate dings, and signals missed while busy are all safe because every re-read queries current state).
The signal is **in-process only**: the ledger is the extension's private upload memory and has no
cross-process watcher, so the backend SHALL NOT post any cross-process (Darwin) notification, and there is
no app-process observer to merge. The seam itself does not change.

#### Scenario: Put dings

- **WHEN** a collector is active on `changes` and `put` completes
- **THEN** the collector receives an emission

#### Scenario: No cross-process notification is posted

- **WHEN** the extension process performs `put`s within a `process()` cycle
- **THEN** no cross-process (Darwin) notification is posted, because no other process observes the ledger
