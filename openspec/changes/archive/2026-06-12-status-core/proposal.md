# Status Core

## Why

The status screen still feeds from a hand-forged stand-in: `SyncStatus` is minted by harness
presets, its counts mean "the most recent pass" (a notion the ledgered engine erased), and
presentation `api()`s the whole engine module to reach three seam types. The engine's ledger —
the single durable truth about what is uploaded — exists since the sync-engine-ledger change but
nothing projects it to the UI. This slice (② of the delivery matrix) builds that projection as
shared code, and lands the ledger's timestamp column while the migration is still free (only
tests consume the database today).

## What Changes

- New module `:domain:status` holding the relocated `SyncStatus`/`SyncState`/`SyncStatusSource`
  (package `app.snapsync.status`) and the new ledger-backed `LedgerSyncStatusSource`;
  `:domain:presentation` drops its `:domain:sync` dependency for `:domain:status` —
  engine types leave presentation's compile classpath.
- Module rename `:domain:sync` → `:domain:engine` (package `app.snapsync.sync` →
  `app.snapsync.engine`, SQLDelight package `app.snapsync.engine.db`) — the two delivery-matrix
  tracks become the two domain module names. Type names keep their `Sync` prefixes.
- The ledger learns to be watched: `LedgerBackend` gains `changes: Flow<Unit>` (a ding after
  every put) and `aggregates(): LedgerAggregates` (pending/completed counts + newest completion,
  one snapshot-consistent query); a third ledger type `LedgerWatcher` emits aggregates on every
  ding. `LedgerReader` stays per-key only.
- Ledger rows gain `updatedAt` (epoch millis, SQLDelight typed columns with adapters), stamped
  by `LedgerWriter` via an injected `Clock` — engine and backends stay clock-free. **BREAKING**
  for the idempotence wording: duplicate record operations now converge on state/attempt/version,
  with the timestamp moving forward.
- `SyncStatus` contract rewrite: counts become lifetime ledger aggregates; classification becomes
  `!active → SUSPENDED, pending>0 → IN_PROGRESS, lastFinishedAt==null → NEVER_SYNCED,
  failed>0 → INCOMPLETE, else COMPLETE`. **BREAKING**: `SyncState.FAILED` and its whole vertical
  (UI state, hero row, harness preset) are deleted — under retry-forever and
  lastFinishedAt-as-newest-completion the state is structurally untellable.
- `active` becomes operational state derived from permission (`== GRANTED`), computed once in
  `LedgerSyncStatusSource` — shared by all platforms, no clocks, no recency heuristics.

## Capabilities

### New Capabilities

- `sync-status`: the status projection — `SyncStatus`/`SyncState` contract (lifetime counts,
  five-state classification), the `SyncStatusSource` seam, and `LedgerSyncStatusSource`
  (watcher-driven, permission-derived `active`, synchronous first value). Classification rules
  move here from `sync-status-screen`.

### Modified Capabilities

- `sync-ledger`: backend grows `changes` ding + `aggregates()`; entries grow `updatedAt`
  (writer-stamped via injected clock); new `LedgerWatcher` type; idempotence scenario reworded
  to state/attempt/version convergence; module/package rename.
- `sync-status-screen`: classification section moves out (now owned by `sync-status`); the
  Failed hero row and its scenarios are deleted; rendering, first-frame, and relative-time
  requirements stay.
- `desktop-test-harness`: sync presets shrink to five reachable states (Failed preset deleted),
  presets forge `active = true` so finished states classify as finished; the gate-interplay
  scenario re-exampled off Failed.

## Impact

- **Modules**: `domain/sync` → `domain/engine` (rename); new `domain/status`;
  `domain/presentation` dependency swap (`api(:domain:status)` + `api(:domain:permission)`,
  no engine dep); `app/desktop` import ripple.
- **Schema**: `ledgerRow` gains `updatedAt INTEGER NOT NULL`; columns become SQLDelight typed
  (`state AS LedgerState`, `updatedAt AS Instant`) with adapter wiring hidden in one factory.
  Free migration — no persisted databases exist outside tests.
- **Out of scope** (deferred to later slices): harness wiring of the real source (the panel keeps
  its preset stand-in), staleness detection (needs platform token bookkeeping), re-read trigger
  implementations (Darwin/foreground — only the backend `changes` seam lands now), any new screen
  states. Slice ④ (JVM status feed/source) is hereby absorbed: its residue is wiring that belongs
  to slices ③ (harness DB instance) and ⑥ (source↔screen composition); the ladder becomes
  ② → ③ → ⑤ → ⑥.
