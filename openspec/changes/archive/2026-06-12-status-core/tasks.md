# Tasks — status-core

## 1. Rename `:domain:sync` → `:domain:engine` (isolated, mechanical)

- [x] 1.1 Move `domain/sync` → `domain/engine`; update `settings.gradle.kts`, dependent
      `build.gradle.kts` files, and the SQLDelight `packageName` to `app.snapsync.engine.db`
- [x] 1.2 Rename package `app.snapsync.sync` → `app.snapsync.engine` across sources and tests;
      fix imports in `:domain:presentation` and `:app:desktop`; full build green

## 2. Ledger: timestamp, aggregates, ding

- [x] 2.1 `Ledger.sq`: typed columns (`state AS LedgerState`, `updatedAt AS Instant` epoch
      millis), `aggregates` query (counts + max completed `updatedAt`, one statement); factory
      function hiding adapter wiring
- [x] 2.2 `LedgerEntry` gains `updatedAt: Instant` (equals/hashCode/toString);
      `LedgerAggregates` value type with value equality
- [x] 2.3 `LedgerBackend` gains `aggregates()` and `changes: Flow<Unit>`; implement in
      `SqlDelightLedgerBackend` (ding after put) and `InMemoryLedgerBackend`
- [x] 2.4 `LedgerWriter` stamps `updatedAt` via injected `Clock` (default system); engine code
      untouched
- [x] 2.5 Contract tests: `updatedAt` round-trip, aggregates scenarios (empty / mixed /
      newest-completion), put-dings; idempotence test reworded to state/attempt/version
      convergence (fixed clock)
- [x] 2.6 `LedgerWatcher`: cold `aggregates: Flow<LedgerAggregates>` (current truth on collect,
      re-query per conflated ding, `distinctUntilChanged`); watcher tests (initial emission,
      re-emit on write, silent on unchanged aggregates)

## 3. `:domain:status` module

- [x] 3.1 Create `domain/status` (commonMain/commonTest, `implementation(:domain:engine)` +
      `implementation(:domain:permission)` + coroutines); register in `settings.gradle.kts`
- [x] 3.2 Move `SyncStatus`/`SyncState`/`SyncStatusSource` to `app.snapsync.status`; rewrite
      contract (lifetime counts, five-state suspended-first classification, FAILED deleted);
      rewrite `SyncStatusTest` as the five-row decision table
- [x] 3.3 `LedgerSyncStatusSource` suspend factory (watcher × permission → `StateFlow`,
      `active = GRANTED`, `failed = 0`, `estimatedRemaining = null`, synchronous first value)
- [x] 3.4 Source tests with real watcher + in-memory backend + fake permission source: initial
      snapshot, re-mint on record, re-mint on permission flip, v1 constants

## 4. Ripple: presentation, harness

- [x] 4.1 `:domain:presentation` swaps `api(:domain:sync)` for `api(:domain:status)`; delete
      `UiState.Failed` and its container mapping; update container tests
- [x] 4.2 `:app:desktop`: delete `showFailed()` preset; finished/virgin presets forge
      `active = true`; screen drops the Failed hero branch; imports updated; harness builds and
      runs
- [x] 4.3 Full build + all tests green (`./gradlew build`)

## 5. Documentation

- [x] 5.1 design.md: §2.1 module graph (engine/status rename + new edges), §2.2 ledger types
      (watcher, updatedAt, ding), §2.4 rewritten to the implemented shape (five states,
      suspended-first, FAILED deletion), §7 table touch-ups
- [x] 5.2 Verify spec deltas still match what was built; adjust if implementation taught
      something
